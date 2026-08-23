package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.loxia.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment

/**
 * 「本月收藏」(TRENDING) / 「当前最热」(RECENT) 的插画·漫画 tab（feeds 框架版，替代 legacy
 * FragmentTrendingIllust / FragmentRecentIllust）。一个类靠 source/type/window 三个 arg 覆盖
 * 全部 4 个插画/漫画 tab，卡片直接复用 [IllustFeedFragment.staggerIllustRenderer]（含热度 pill）。
 *
 * 数据走自建 shaft-api-v2、响应不实现 KListShow，所以数据源是手写的 [HotWorksIllustFeedSource]
 * 而非样板里的 cachedPixivFeedSource（无本地缓存——热度榜是实时/慢变量，legacy 也没缓存）。
 * 懒加载（宿主 ViewPager 用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT），tab 可见才拉。
 */
class HotWorksIllustFeedFragment : IllustFeedFragment() {

    private val source: HotWorksSource by lazy(LazyThreadSafetyMode.NONE) {
        HotWorksSource.ofName(requireArguments().getString(ARG_SOURCE))
    }
    private val type: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_TYPE) ?: TYPE_ILLUST
    }
    private val window: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_WINDOW)
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：只捕获局部值，不把 Fragment 钉进 VM。
        val source = source
        val type = type
        val window = window
        HotWorksIllustFeedSource(source, type, window)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL，不是 app-api illust nextUrl；别漏进详情页 pager
    // （getNextIllust 拿它当 @Url 请求会拿到 TrendingWorksResponse 形状，解析成空 IllustResponse）。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照：is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信，喂池会把当前用户更新的收藏/关注态盖回去。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    companion object {
        /** 服务端 enum，不是展示文案，别本地化。 */
        const val TYPE_ILLUST = "illust"
        const val TYPE_MANGA = "manga"

        private const val ARG_SOURCE = "hot_source"
        private const val ARG_TYPE = "hot_type"
        private const val ARG_WINDOW = "hot_window"

        /** window：仅 RECENT 用，null=实时流，否则 day|week|month；TRENDING 传 null。 */
        @JvmStatic
        fun newInstance(
            source: HotWorksSource,
            type: String,
            window: String?,
        ): HotWorksIllustFeedFragment {
            return HotWorksIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE, source.name)
                    putString(ARG_TYPE, type)
                    putString(ARG_WINDOW, window)
                }
            }
        }
    }
}
