package ceui.pixiv.witstudio.theme

import android.content.Context
import kotlin.math.roundToInt

/** 替代 `QMUIDisplayHelper`。项目里只用到了 `dp2px`，所以只提供这一个。 */
public object WitDisplay {

    @JvmStatic
    public fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()
}
