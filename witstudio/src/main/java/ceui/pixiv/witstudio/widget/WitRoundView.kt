package ceui.pixiv.witstudio.widget

import androidx.annotation.ColorInt

/**
 * 圆角控件的公共可编程接口（[WitRoundButton] / [WitRoundLinearLayout] / [WitRoundRelativeLayout]）。
 *
 * XML 侧用 `wit_*` 属性配置（见 res/values/wit_attrs.xml），这里是运行时改的入口。
 */
public interface WitRoundView {

    /** 换填充色。 */
    public fun setWitBackgroundColor(@ColorInt color: Int)

    /** 换描边。[widthPx] 传 0 即取消描边。 */
    public fun setWitBorder(widthPx: Int, @ColorInt color: Int)

    /** 四角统一圆角。若开了 `wit_isRadiusAdjustBounds` 则本次设置会在下次布局时被覆盖。 */
    public fun setWitRadius(radiusPx: Float)

    /** 四角分别设置，顺序同 [android.graphics.drawable.GradientDrawable.setCornerRadii]。 */
    public fun setWitRadii(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float)
}
