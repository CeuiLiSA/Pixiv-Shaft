package ceui.lisa.utils

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.R as AppCompatR

/**
 * 系统栏 / 工具栏尺寸。纯函数，每次按当前 Context 现算——以前是 Shaft 启动时算一次
 * 存进两个 static int，折叠屏 / 多窗口 / 配置变化后就是错的。
 */
object SystemBarMetrics {

    @JvmStatic
    fun statusBarHeight(context: Context): Int {
        val res = context.resources
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /** `?attr/actionBarSize`；主题里没定义时回退 56dp。 */
    @JvmStatic
    fun toolbarHeight(context: Context): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(AppCompatR.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data, context.resources.displayMetrics)
        }
        return (56f * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
