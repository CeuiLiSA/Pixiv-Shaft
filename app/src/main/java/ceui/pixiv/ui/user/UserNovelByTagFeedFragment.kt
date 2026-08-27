package ceui.pixiv.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ImageUrls
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.loxia.UserTagNovel
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * issue #996：某作者「按 Tag 筛选」后的小说列表——插画侧 [UserIllustByTagFeedFragment]
 * 的小说对应物，同一套网页 ajax 路数（/ajax/user/{id}/novels/tag，offset 翻页），把精简的
 * 网页小说对象映射成 loxia [Novel] 复用基类的主力小说卡。点进阅读只按 id 走详情接口，
 * 不依赖这份精简数据的完整性。
 *
 * 空态的网页登录引导、登录回来自动重拉，两套逻辑与插画侧同因同解（数据源是 www.pixiv.net
 * 的 ajax，匿名视角看不到敏感作品），注释详见插画侧，这里不重复。
 */
class UserNovelByTagFeedFragment : NovelFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }
    // 命名避开 Fragment.getTag()（同 JVM 签名会被判「accidental override」）。
    private val filterTag: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.KEY_WORD).orEmpty()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：source 只吃 Long userId + String tag。
        UserNovelByTagFeedSource(userId, filterTag)
    }

    override val emptyStateText: CharSequence
        get() = if (SessionManager.hasWebCookie) {
            super.emptyStateText
        } else {
            getString(R.string.user_tag_filter_empty_need_web_login)
        }

    override val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() = if (SessionManager.hasWebCookie) {
            null
        } else {
            getString(R.string.street_web_login_confirm) to {
                startActivity(
                    Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "Web首页")
                        putExtra(Params.AUTO_WEB_LOGIN, true)
                    }
                )
            }
        }

    /** null = 还没 pause 过；理由与插画侧同（别让首次 onResume 把 autoLoad 的首屏重发一遍）。 */
    private var hadWebCookieOnPause: Boolean? = null

    override fun onPause() {
        super.onPause()
        hadWebCookieOnPause = SessionManager.hasWebCookie
    }

    override fun onResume() {
        super.onResume()
        if (hadWebCookieOnPause == false && SessionManager.hasWebCookie) {
            feedViewModel.refresh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = if (filterTag.isEmpty()) "" else "#$filterTag"
    }

    companion object {
        @JvmStatic
        fun newInstance(userId: Long, tag: String?): UserNovelByTagFeedFragment {
            return UserNovelByTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                    putString(Params.KEY_WORD, tag)
                }
            }
        }
    }
}

/**
 * 按 Tag 筛选作者小说的数据源：网页 ajax（[Client.webApi]），offset 翻页。
 * 每页精简 work → loxia [Novel] → [NovelFeedItem.of]（含全局内容过滤）。
 * 游标 = 下一页 offset（已加载条数）；works 空或已到 total 则停。零 Fragment 捕获。
 */
class UserNovelByTagFeedSource(
    private val userId: Long,
    private val tag: String,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val offset = cursor?.toIntOrNull() ?: 0
        val resp = Client.webApi
            .getUserNovelsByTag(userId, tag, offset, PAGE_SIZE)
        // 网页 ajax 的业务错误是 HTTP 200 + error:true + body:null，同插画侧：
        // 抛出去交给 feeds 的错误态,别渲染成「筛出来 0 件」。
        if (resp.error == true) {
            throw RuntimeException(
                resp.message.orEmpty().ifEmpty { "user/novels/tag failed" }
            )
        }
        val body = resp.body
        val works = body?.works ?: emptyList()
        val total = body?.total ?: 0
        val loaded = offset + works.size
        // 映射 + 内容过滤挪 Default，保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            works.mapNotNull { NovelFeedItem.of(it.toNovel(), skipMuteUserFilter = true) }
        }
        val next = if (works.isNotEmpty() && loaded < total) loaded.toString() else null
        return FeedPage(items, next)
    }

    companion object {
        private const val PAGE_SIZE = 48
    }
}

/**
 * 网页精简小说 → loxia [Novel]。封面本就是 novel-cover-master 的 600x600，与 app-api 缩略图
 * 同一 CDN，直接三档同填；tags 只有字符串名，无译名。visible 置 true 避免被当不可见滤掉。
 *
 * 系列信息网页也给（seriesId/seriesTitle，单篇为 null），照填 —— 不填的话本页每张卡都缺
 * 主力小说卡的「系列」那一行，同一本小说在别的列表里有、在这里没有。
 */
internal fun UserTagNovel.toNovel(): Novel {
    val cover = url?.takeIf { it.isNotEmpty() }
    val series = seriesId?.toLongOrNull()?.let { Series(id = it, title = seriesTitle) }
    return Novel(
        id = id,
        title = title ?: "",
        caption = description,
        create_date = createDate,
        image_urls = cover?.let { ImageUrls(large = it, medium = it, square_medium = it) },
        tags = tags?.map { Tag(name = it) } ?: emptyList(),
        series = series,
        text_length = textCount,
        total_bookmarks = bookmarkCount,
        // 已同步网页 cookie 时 bookmarkData 非 null = 已收藏;匿名视角恒 null,
        // 与插画侧同理属精简数据的已知局限,点进详情会按 id 拉全量。
        is_bookmarked = bookmarkData != null,
        user = User(
            id = userId,
            name = userName ?: "",
            profile_image_urls = profileImageUrl?.takeIf { it.isNotEmpty() }
                ?.let { ImageUrls(medium = it) },
        ),
        visible = true,
        x_restrict = xRestrict,
        novel_ai_type = aiType,
    )
}
