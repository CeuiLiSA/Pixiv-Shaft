package ceui.pixiv.witstudio.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import ceui.pixiv.witstudio.widget.internal.WitRoundHelper

/**
 * 圆角 [LinearLayout]，替代 `QMUIRoundLinearLayout`。默认不自动胶囊（圆角只认 `wit_radius*`）。
 *
 * ⚠️ 背景是一个裸 `GradientDrawable`，这是外部约定的一部分：
 * `ceui.lisa.fragments.FragmentRight#tintContentSheet` 靠强转它来给右抽屉染主题色。
 * 详见 [WitRoundHelper] 的类注释。
 */
public class WitRoundLinearLayout @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), WitRoundView {

    private val helper = WitRoundHelper(
        this, context, attrs, defStyleAttr,
        defaultRadiusAdjustBounds = false,
        defaultBorderWidthPx = 0,
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        helper.onSizeChanged(w, h)
    }

    override fun setWitBackgroundColor(color: Int): Unit = helper.setBackgroundColor(color)

    override fun setWitBorder(widthPx: Int, color: Int): Unit = helper.setBorder(widthPx, color)

    override fun setWitRadius(radiusPx: Float): Unit = helper.setRadius(radiusPx)

    override fun setWitRadii(
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
    ): Unit = helper.setRadii(topLeft, topRight, bottomRight, bottomLeft)
}
