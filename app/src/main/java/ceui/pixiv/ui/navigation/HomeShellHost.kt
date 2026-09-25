package ceui.pixiv.ui.navigation

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import ceui.pixiv.witstudio.theme.dp

/**
 * 首页（MainActivity）对各 tab 公开的排版形态：窗口够宽时底栏换成 [HomeNavigationRail]（#1087）。
 *
 * 只在平板上换形态：窗口最小宽度 >= 600dp（手机横竖屏都不命中，手机 UI 完全不变），且当前
 * 可用宽度也 >= 600dp（平板分屏、Activity Embedding 双栏里的窄窗口仍是手机排版）。
 * MainActivity 自己处理 screenSize 配置变化、不重建，所以 tab 页要订阅这个值，而不是在
 * onCreateView 里读一次。
 */
interface HomeShellHost {

    /** true = 侧边导航栏在显示（宽窗口）；false = 底栏（手机排版）。 */
    val navigationRailShown: LiveData<Boolean>

    companion object {

        /** 平板判定与侧栏的宽度下限：Android 窗口尺寸档位里 medium 的下限。 */
        const val RAIL_MIN_WIDTH_DP = 600

        @JvmStatic
        fun isRailWidth(configuration: Configuration): Boolean =
            configuration.smallestScreenWidthDp >= RAIL_MIN_WIDTH_DP &&
                configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP

        /** tab 页订阅宿主形态；宿主不是首页（比如被别处复用）时按手机排版回调一次。 */
        @JvmStatic
        fun observe(fragment: Fragment, onChanged: Consumer<Boolean>) {
            val host = fragment.activity as? HomeShellHost
            if (host == null) {
                onChanged.accept(false)
                return
            }
            host.navigationRailShown.observe(fragment.viewLifecycleOwner) { onChanged.accept(it) }
        }

        /**
         * 发现 / 动态 / 推荐三个 tab 共用的顶部标题行：侧栏里已经有菜单入口，宽窗口下隐藏
         * 标题行自己的抽屉按钮，标题左缘改对齐 24dp 页边距（行 paddingStart 18 + 标题 marginStart 6）。
         */
        @JvmStatic
        fun adaptHeaderRow(drawerButton: View, railShown: Boolean) {
            drawerButton.visibility = if (railShown) View.GONE else View.VISIBLE
            val row = drawerButton.parent as? ViewGroup ?: return
            val start = row.context.dp(if (railShown) 18 else 12)
            row.setPaddingRelative(start, row.paddingTop, row.paddingEnd, row.paddingBottom)
        }
    }
}
