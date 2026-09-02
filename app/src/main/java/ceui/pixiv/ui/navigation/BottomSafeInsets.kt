package ceui.pixiv.ui.navigation

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 给「铺到屏幕底」的滚动容器按底部安全区补 paddingBottom。
 *
 * 安全区是谁由宿主分发的 inset 决定：普通页面是手势条 / 导航栏，首页则是浮在内容之上、会跟随
 * 滚动收起的底栏（[BottomBarAutoHide]，MainActivity 把底栏高度重写进了分发给内容区的 inset）。
 * 和 FeedFragment.applyBottomSafeInset 是同一套读法，这里供不走 feeds 那套骨架的页面用。
 *
 * 调用方要保证容器 clipToPadding=false，否则底栏收起后露出的是一条容器底色而不是内容。
 */
object BottomSafeInsets {

    @JvmStatic
    fun applyTo(scroller: View) {
        val basePaddingBottom = scroller.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { v, windowInsets ->
            val bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = basePaddingBottom + bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(scroller)
    }
}
