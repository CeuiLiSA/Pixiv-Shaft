package ceui.pixiv.witstudio.theme

import android.content.Context
import kotlin.math.roundToInt

/** 替代 `QMUIDisplayHelper`。项目里只用到了 `dp2px`，所以只提供这一个。 */
public object WitDisplay {

    @JvmStatic
    public fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()

    /**
     * 不取整的 dp→px。给 [android.graphics.drawable.GradientDrawable.setCornerRadius] 这类
     * 本来就吃 float 的地方用，省得先 roundToInt 再 toFloat 白丢精度。
     */
    @JvmStatic
    public fun dp2pxF(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density

    /** 0.5dp 这种亚像素 hairline：向下取整会变 0（描边整条消失），所以兜到 1px。 */
    @JvmStatic
    public fun hairline(context: Context): Int =
        (0.5f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
