package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.lisa.models.IllustsBean
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment

/**
 * 标签专区的 feed 子页(feeds 框架版)。宿主 [TagRankFragment] 按所选标签把本 fragment
 * replace 进 feed_container,一次只有一个在场;无参 [IllustFeedFragment](toolbar 在宿主),
 * 数据走 [BookmarkRankFeedSource] 带 ?tag=。
 *
 * `autoLoad = false`:replace 进来即 RESUMED,首屏由 FeedFragment.onResume 的
 * ensureLoaded 拉起,单 feed 在场时与 autoLoad=true 行为等价 —— 保持 false 是对齐
 * 这组榜单子页的既定组合(见 [YearRankIllustFeedFragment])。
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
