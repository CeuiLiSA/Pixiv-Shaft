package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 壁纸榜的**单个 screen tab**(feeds 框架版)。宿主是 [WallpaperRankFragment] 的 ViewPager,
 * 无参 [IllustFeedFragment](toolbar 在宿主)。`autoLoad = false`:只有两个 tab,一次两枪
 * 倒是打不死限流,但 RESUME_ONLY_CURRENT + 手动加载是这组榜单子页的既定组合
 * (论证见 [YearRankIllustFeedFragment]),没理由单独破例。
 */
class WallpaperIllustFeedFragment : IllustFeedFragment() {

    private val screen: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_SCREEN) ?: WallpaperRankFragment.SCREEN_PHONE
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val s = screen
        WallpaperFeedSource(screen = s)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照,收藏/关注态不可信,不喂池。同 BookmarkRankFeedFragment。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    companion object {
        private const val ARG_SCREEN = "wallpaper_screen"

        /** [screen] 取 [WallpaperRankFragment.SCREEN_PHONE] / [WallpaperRankFragment.SCREEN_DESKTOP]。 */
        @JvmStatic
        fun newInstance(screen: String): WallpaperIllustFeedFragment {
            return WallpaperIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCREEN, screen)
                }
            }
        }
    }
}

/**
 * 壁纸榜数据源:shaft-api-v2 discover/wallpapers(首屏 wallpapers,翻页 wallpapersByUrl)。
 * item 形状同收藏榜,map 逻辑对齐 [BookmarkRankFeedSource](pill 显 pixiv 总收藏数、
 * 收藏态清零);内容过滤全部照常生效(壁纸榜不是 AI/R18 专属榜,没有让步的理由)。
 */
class WallpaperFeedSource(
    private val screen: String,
    private val limitN: Int = 30,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.WallpapersResponse = if (cursor == null) {
            ShaftApiV2Client.service.wallpapers(screen = screen, limit = limitN)
        } else {
            ShaftApiV2Client.service.wallpapersByUrl(cursor)
        }
        // gson 解析 + 内容过滤挪 Default,保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull { mapWallpaperItem(it) }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** item.bean → IllustFeedItem(跑在 Default、纯函数、零捕获)。 */
        private fun mapWallpaperItem(item: ShaftApiV2.TrendingWorkItem): IllustFeedItem? {
            val json = item.bean ?: return null
            val bean = try {
                Shaft.sGson.fromJson(json, Illust::class.java)
            } catch (e: Throwable) {
                Timber.tag("WallpaperRank").w(e, "skip malformed bean id=${item.target_id}")
                return null
            } ?: return null
            return IllustFeedItem.of(
                bean.withTrendingScore(item.bookmark_count.toFloat()).withBookmarked(false)
            )
        }
    }
}
