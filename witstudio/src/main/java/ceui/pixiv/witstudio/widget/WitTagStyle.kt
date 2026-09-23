package ceui.pixiv.witstudio.widget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette

/** 与原 V3TagFlowView 共用的主题胶囊，可用于业务自行管理选中态的筛选项。 */
public object WitTagStyle {
    private val active = intArrayOf(android.R.attr.state_activated)
    private val checked = intArrayOf(android.R.attr.state_checked)

    @JvmStatic
    public fun background(palette: V3Palette, density: Float): Drawable {
        // 单行仍是胶囊；窄屏大字换成多行时封顶圆角，避免整块变椭圆、文字伸出底色。
        val radius = 24f * density
        val states = StateListDrawable().apply {
            addState(active, palette.pillSecondary(radius, density.toInt().coerceAtLeast(1)))
            addState(checked, palette.pillSecondary(radius, density.toInt().coerceAtLeast(1)))
            addState(intArrayOf(), palette.tagLockedBg(radius))
        }
        return RippleDrawable(ColorStateList.valueOf(palette.alpha20), states,
            palette.pillPrimary(radius).apply { setColor(Color.WHITE) })
    }

    @JvmStatic
    public fun applyText(view: TextView, palette: V3Palette) {
        view.setTextColor(palette.textAccent)
        view.typeface = ResourcesCompat.getFont(view.context, R.font.montserrat_medium)
    }
}
