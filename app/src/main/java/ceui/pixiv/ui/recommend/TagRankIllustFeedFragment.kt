package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.lisa.models.IllustsBean
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment

/**
 * 标签专区的**单个 tag tab**(feeds 框架版)。宿主是 [TagRankFragment] 的 ViewPager,
 * 无参 [IllustFeedFragment](toolbar 在宿主),数据走 [BookmarkRankFeedSource] 带 ?tag=。
 *
 * ⚠️ `autoLoad = false` 不是可选的 —— 30 个 tag tab,理由同 [YearRankIllustFeedFragment]
 * (读端点限流 120 req/min/IP + CN 运营商级 NAT,自动加载会一次打 30 枪)。
 */
class TagRankIllustFeedFragment : IllustFeedFragment() {

    private val tagName: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_TAG).orEmpty()
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val tag = tagName
        BookmarkRankFeedSource(tag = tag)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager
    // (getNextIllust 拿它当 @Url 请求会拿到 MostBookmarkedResponse 形状,解析成空 IllustResponse)。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照:is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信,喂池会把当前用户更新的收藏/关注态盖回去。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<IllustsBean> = emptyList()

    companion object {
        private const val ARG_TAG = "tag_rank_tag"

        /** [tag] 是服务端原文 tag 名(enum 语义,不是展示文案),别本地化、别传 translated。 */
        @JvmStatic
        fun newInstance(tag: String): TagRankIllustFeedFragment {
            return TagRankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG, tag)
                }
            }
        }
    }
}
