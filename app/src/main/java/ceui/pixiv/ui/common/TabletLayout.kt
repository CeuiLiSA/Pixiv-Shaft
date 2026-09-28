package ceui.pixiv.ui.common

import android.content.res.Configuration
import ceui.lisa.activities.Shaft

/**
 * 平板排版（#1087：首页侧边导航栏、瀑布流按宽度加列、作品详情舞台）的总开关。
 *
 * 平板 = 窗口最小宽度 >= 600dp，与 layout-sw600dp / values-sw600dp 同一条线，手机横竖屏都不命中。
 * 设置 · 界面 ·「平板适配排版」关闭时平板也按手机排版走；各处都是渲染时读，不缓存。
 */
object TabletLayout {

    /** Android 窗口尺寸档位里 medium 的下限。 */
    const val MIN_WIDTH_DP = 600

    @JvmStatic
    fun isEnabled(configuration: Configuration): Boolean =
        Shaft.sSettings.isTabletLayout && configuration.smallestScreenWidthDp >= MIN_WIDTH_DP
}
