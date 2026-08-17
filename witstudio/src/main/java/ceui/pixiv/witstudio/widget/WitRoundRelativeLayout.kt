package ceui.pixiv.witstudio.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import ceui.pixiv.witstudio.widget.internal.WitRoundHelper

/**
 * 圆角 [RelativeLayout]，替代 `QMUIRoundRelativeLayout`。默认不自动胶囊。
 * 背景同样是裸 `GradientDrawable`，约束见 [WitRoundHelper]。
 */
public class WitRoundRelativeLayout @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr), WitRoundView {

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
