package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment

/**
 * 「本月收藏」(TRENDING) / 「当前最热」(RECENT) 的小说 tab（feeds 框架版，替代 legacy
 * FragmentTrendingNovel / FragmentRecentNovel）。复用 [NovelFeedFragment] 的主力小说卡
 * （热度分经 NovelFeedItem.trendingScore 露 pill）。数据源手写 [HotWorksNovelFeedSource]
 * （shaft-api-v2，type=novel）。无 ranking 头、无缓存、懒加载，同插画 tab。
 */
class HotWorksNovelFeedFragment : NovelFeedFragment() {

    private val source: HotWorksSource by lazy(LazyThreadSafetyMode.NONE) {
        HotWorksSource.ofName(requireArguments().getString(ARG_SOURCE))
    }
    private val window: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_WINDOW)
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val source = source
        val window = window
        HotWorksNovelFeedSource(source, window)
    }

    companion object {
        private const val ARG_SOURCE = "hot_source"
        private const val ARG_WINDOW = "hot_window"

        @JvmStatic
        fun newInstance(source: HotWorksSource, window: String?): HotWorksNovelFeedFragment {
            return HotWorksNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE, source.name)
                    putString(ARG_WINDOW, window)
                }
            }
        }
    }
}
