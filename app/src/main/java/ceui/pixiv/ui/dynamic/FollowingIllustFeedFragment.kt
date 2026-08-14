package ceui.pixiv.ui.dynamic

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedTimelineSkeletonView
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem

/**
 * 「动态」页的插画/漫画列表（feeds 框架版，替代 legacy FragmentRight 自身的
 * NetListFragment + RightRepo + IAdapter/TimelineAdapter 那一套）。
 * 宿主是 [ceui.lisa.fragments.FragmentRight]，装在它的 `illust_list_container` 里。
 *
 * 两件本页特有的事，其余（收藏、长按菜单、详情 pager 续拉回流、合池）全部继承自
 * [IllustFeedFragment]：
 *
 * 1. **筛选范围**（全部 / 公开 / 私人）：宿主的筛选条通过 [setRestrict] 推进来。
 *    值存在 [RestrictViewModel] 而不是 Fragment 字段——数据源归 FeedViewModel 长期持有、
 *    比 Fragment 实例活得久，按零捕获约定不能读 Fragment 字段；而 [RestrictViewModel] 与
 *    FeedViewModel 同一个 store、同生共死，捕获它既不漏也不会读到上一代的值。
 * 2. **两种排布**：时间线（单列大卡）/ 瀑布流，由设置「关注动态布局模式」持久化，
 *    宿主的按钮切换后调 [applyLayoutMode] —— 只重装 Renderer + LayoutManager，
 *    数据留在 VM 里**不重拉**（对齐 legacy 换 adapter 的语义）。
 */
class FollowingIllustFeedFragment : IllustFeedFragment() {

    private val restrictViewModel: RestrictViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        // 取成局部 val:捕获的是 VM 实例(与 FeedViewModel 同 store、同寿命),不是 Fragment
        val holder = restrictViewModel
        pixivFeedSource({ Client.appApi.getFollowingIllusts(holder.restrict) }) { resp, _ ->
            resp.displayList.mapNotNull { IllustFeedItem.from(it) }
        }
    }

    /**
     * 本列表嵌在动态页那张圆角 sheet 里，底色必须跟 sheet 一致，否则筛选条下方会裂出一道
     * 撞色横缝（裸 fragment_feed 的基类底色默认是整页的 v3_bg）。
     *
     * sheet 用 [V3Palette.cardFill]（隐约带主题色的不透明悬浮底，日夜双模）而不是静态的
     * v3_menu_bg——后者夜间写死 #1A1A2E 是藏青，主题色换成绿/粉时就成了页面上一条突兀的蓝带。
     * 这里与 FragmentRight 给 content_item 上色取的是同一个值，两边必须同源。
     */
    override val feedRootBackgroundColor: Int
        get() = V3Palette.from(requireContext()).cardFill

    /** 时间线模式 = 单列大卡；关掉就是瀑布流。真源是设置项，设置页「关注动态布局模式」同一个开关。 */
    private val isTimelineMode: Boolean
        get() = !Shaft.sSettings.isUseStaggeredLayout()

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return if (isTimelineMode) {
            LinearLayoutManager(requireContext())
        } else {
            super.onCreateLayoutManager()
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return if (isTimelineMode) {
            listOf(
                timelineIllustRenderer(
                    onClick = { item -> openDetail(item) },
                    // 打码的卡点一下 = 取消屏蔽，同瀑布流卡
                    onUnmute = { item -> setIllustMuted(item, false) },
                )
            )
        } else {
            super.onCreateRenderers()
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 瀑布流卡自身无 margin,靠基类的 8dp SpacesItemDecoration 拉开;
        // 时间线大卡自带内边距和分隔线,再加 decoration 会双份留白
        if (!isTimelineMode) {
            super.onListReady(listView)
        }
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView? {
        // 按 LayoutManager 判而不是再读一次设置:骨架必须和这次装配出来的列表长得一样,
        // 不能有第二个真源(哪怕理论上读不出不一致,也别留这种耦合)
        return if (layoutManager is StaggeredGridLayoutManager) {
            super.onCreateSkeletonView(layoutManager)
        } else {
            FeedTimelineSkeletonView(requireContext())
        }
    }

    /**
     * 切筛选范围（宿主筛选条选中另一项时调）。对齐 legacy 的
     * `RightRepo.setRestrict + forceRefresh`：**变了才重拉**，没变是 no-op。
     */
    fun setRestrict(restrict: String) {
        if (restrictViewModel.restrict == restrict) return
        restrictViewModel.restrict = restrict
        // forceRefresh 在 view 未创建时安全 no-op:此时数据也还没拉过,
        // 首屏会自然用上面刚写进去的新 restrict
        forceRefresh()
    }

    /** 时间线 / 瀑布流切换后重装列表（宿主写完设置项再调）。数据不重拉。 */
    fun applyLayoutMode() {
        rebuildList()
    }
}
