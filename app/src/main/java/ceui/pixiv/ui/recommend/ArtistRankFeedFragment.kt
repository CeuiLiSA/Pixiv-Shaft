package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** total=总收藏榜 / avg=平均收藏榜（质量派，作品≥20）。 */
private const val SORT_TOTAL = "total"
private const val SORT_AVG = "avg"

/**
 * 画师排行（feeds 框架版，替代 legacy FragmentArtistRank + ArtistRankRepo + UAdapter）。
 * 打自建 shaft-api-v2 的 discover/artists，含 R-18；两种口径由 [SORT_TOTAL] / [SORT_AVG] 决定，
 * TemplateActivity 用同一个 Fragment 承载「画师榜」「画师均分榜」两个入口。
 *
 * 自带 toolbar（fragment_toolbar_feed），复用 [UserFeedFragment] 的用户卡渲染（画师 + 3 张代表作
 * 缩略图）/ 关注切换 / LIKED_USER 广播同步 / 点击进画师页。
 */
class ArtistRankFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // 只认 avg，其余（含缺参）一律回落 total——对齐 legacy initBundle 的兜底。
    private val sort: String by lazy(LazyThreadSafetyMode.NONE) {
        if (requireArguments().getString(Params.DATA_TYPE) == SORT_AVG) SORT_AVG else SORT_TOTAL
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source
        val sort = sort
        ArtistRankFeedSource(sort)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(
            if (sort == SORT_AVG) R.string.artist_avg_rank_title else R.string.artist_rank_title
        )
    }

    companion object {
        @JvmStatic
        fun newInstance(sort: String): ArtistRankFeedFragment {
            return ArtistRankFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(Params.DATA_TYPE, sort)
                }
            }
        }
    }
}

/**
 * 画师榜数据源：shaft-api-v2 discover/artists（首屏 discoverArtists，翻页 discoverArtistsByUrl）。
 * 响应 [ShaftApiV2.ArtistRankResponse] 不实现 KListShow（user / illusts 是原始 pixiv JsonObject），
 * 用不了 PixivFeedSource，手写 [FeedSource]（同浏览量榜 [ViewRankFeedSource]）。
 *
 * 逐条把原始 pixiv JSON 直接反序列化成 [User] / [Illust] 拼 [UserPreview]；feeds 侧
 * [UserFeedItem] 本就收 [UserPreview]，无需中间模型往返。用 [Shaft.sGson]（vanilla Gson，
 * 无自定义适配器）与其他缓存读取路径保持一致。
 *
 * 清零关注/收藏态：payload 里的 is_followed / is_bookmarked 是上报榜单那个客户端当时的状态，跟当前
 * 用户无关，全清成 false 让用户能以自己名义关注（同 legacy ArtistRankRepo / ViewRankFeedSource）。
 *
 * 零 Fragment 捕获（sort 是构造进来的局部值，map 是伴生纯函数）。
 */
class ArtistRankFeedSource(
    private val sort: String = SORT_TOTAL,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.ArtistRankResponse = if (cursor == null) {
            ShaftApiV2Client.service.discoverArtists(sort = sort)
        } else {
            ShaftApiV2Client.service.discoverArtistsByUrl(cursor)
        }
        // gson 解析（每条画师 1 个 user + N 个 illust）挪 Default，保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            resp.user_previews.mapNotNull { mapArtistPreview(it) }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** ArtistPreviewItem → UserFeedItem（跑在 Default、纯函数、零捕获）。 */
        private fun mapArtistPreview(item: ShaftApiV2.ArtistPreviewItem): UserFeedItem? {
            val userJson = item.user ?: return null
            val user = try {
                Shaft.sGson.fromJson(userJson, User::class.java)
            } catch (e: Throwable) {
                Timber.tag("ArtistRank").w(e, "skip malformed user user_id=${item.user_id}")
                return null
            } ?: return null
            // UserFeedItem.feedKey = user.id：id 缺失的脏条目会全挤成同一个身份(0)，被框架的
            // dedupByIdentity 折叠成一条，与其静默丢内容不如直接跳过。
            if (user.id == 0L) return null
            val illusts = item.illusts.mapNotNull { json ->
                try {
                    Shaft.sGson.fromJson(json, Illust::class.java)?.copy(is_bookmarked = false)
                } catch (e: Throwable) {
                    null
                }
            }
            // novels 给空列表：legacy 那句 setNovels(emptyList()) 是为了兜 UAdapter 在插画不足 3 张时
            // 读 getNovels().subList(...) 的 NPE；feeds 的用户卡只渲染 illusts（不足留空，见
            // UserFeedFragment），已无此坑，这里保持空列表只为与 legacy 语义一致（画师榜不出小说）。
            return UserFeedItem(
                UserPreview(
                    illusts = illusts,
                    user = user.copy(is_followed = false),
                    novels = emptyList(),
                )
            )
        }
    }
}
