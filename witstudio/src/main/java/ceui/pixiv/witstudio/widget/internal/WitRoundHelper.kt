package ceui.pixiv.witstudio.widget.internal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import ceui.pixiv.witstudio.R

/**
 * [ceui.pixiv.witstudio.widget.WitRoundButton] / `WitRoundLinearLayout` / `WitRoundRelativeLayout`
 * 三者共享的圆角背景逻辑。三个控件各自只剩十来行壳。
 *
 * # 两条承重约束，改之前先读
 *
 * **1. 背景必须是一个裸 [GradientDrawable]，不能包 Ripple/Layer，也不能换成自定义 Drawable 子类。**
 * `ceui.lisa.fragments.FragmentRight#tintContentSheet` 会把
 * `contentItem.getBackground()` 强转成 `GradientDrawable` 再 `setColor(...)` 给右抽屉染主题色。
 * 那处有 `instanceof` 保护，所以结构不符时**不会崩，只会静默失去主题色跟随**。
 *
 * **2. 全程复用同一个 drawable 实例，绝不在尺寸变化时重建。**
 * 上面那种外部 mutate 一旦发生，重建就会把它冲掉（表现为「刚进页面是对的，转屏/复用后变回默认色」）。
 * 所以 [onSizeChanged] 只改 cornerRadius，不碰颜色，也不 new。
 */
internal class WitRoundHelper(
    private val view: View,
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defaultRadiusAdjustBounds: Boolean,
    /**
     * 布局里没写 `wit_borderWidth` 时用的宽度。按钮传 1dp、容器传 0 ——
     * 这正是 QMUI 通过 `QMUI.RoundButton` 样式只给按钮设默认值造成的区别，
     * 关注 / 取关按钮的描边就靠它（见 res/values/wit_dimens.xml 的说明）。
     */
    defaultBorderWidthPx: Int,
) {

    /** 唯一的背景实例。见类注释约束 2。 */
    val drawable: GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    private var radiusAdjustBounds: Boolean = defaultRadiusAdjustBounds
    private var borderWidthPx: Int = 0

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.WitRoundView, defStyleAttr, 0)
        try {
            radiusAdjustBounds = a.getBoolean(
                R.styleable.WitRoundView_wit_isRadiusAdjustBounds, defaultRadiusAdjustBounds
            )
            borderWidthPx = a.getDimensionPixelSize(
                R.styleable.WitRoundView_wit_borderWidth, defaultBorderWidthPx
            )

            // getColorStateList 对普通色值也返回单态 CSL，所以两种写法都吃得下。
            val bg = a.getColorStateList(R.styleable.WitRoundView_wit_backgroundColor)
            if (bg != null) drawable.color = bg

            val borderColor = a.getColorStateList(R.styleable.WitRoundView_wit_borderColor)
            applyStroke(borderWidthPx, borderColor)

            // 单角优先：只要写了任意一个单角属性，就走 cornerRadii，忽略统一的 wit_radius
            //（和 QMUI 的取舍一致，避免「同时写了两种、结果取哪个」的歧义）。
            val tl = a.getDimensionPixelSize(R.styleable.WitRoundView_wit_radiusTopLeft, 0)
            val tr = a.getDimensionPixelSize(R.styleable.WitRoundView_wit_radiusTopRight, 0)
            val bl = a.getDimensionPixelSize(R.styleable.WitRoundView_wit_radiusBottomLeft, 0)
            val br = a.getDimensionPixelSize(R.styleable.WitRoundView_wit_radiusBottomRight, 0)
            if (tl > 0 || tr > 0 || bl > 0 || br > 0) {
                radiusAdjustBounds = false
                setRadii(tl.toFloat(), tr.toFloat(), br.toFloat(), bl.toFloat())
            } else {
                val radius = a.getDimensionPixelSize(R.styleable.WitRoundView_wit_radius, 0)
                if (radius > 0) {
                    radiusAdjustBounds = false
                    drawable.cornerRadius = radius.toFloat()
                }
            }
        } finally {
            a.recycle()
        }
        setBackgroundKeepingPadding()
    }

    /**
     * `View.setBackground` 会拿新 drawable 的 padding 覆盖掉视图当前的 padding，而
     * [GradientDrawable] 报告的 padding 是 0 —— 直接设会把布局里写的
     * `android:paddingStart/End` 全抹平（关注按钮的左右 16dp 就是这么丢的）。
     * 先存后恢复，同 QMUI 的 `QMUIViewHelper.setBackgroundKeepingPadding`。
     */
    private fun setBackgroundKeepingPadding() {
        val padding = intArrayOf(
            view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom
        )
        view.background = drawable
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    private fun applyStroke(widthPx: Int, color: ColorStateList?) {
        borderWidthPx = widthPx
        if (widthPx > 0 && color != null) {
            drawable.setStroke(widthPx, color)
        } else {
            // 宽度 0 时显式清掉，避免复用视图残留上一次的描边。
            drawable.setStroke(0, ColorStateList.valueOf(0))
        }
    }

    fun setBackgroundColor(color: Int) {
        drawable.color = ColorStateList.valueOf(color)
    }

    fun setBorder(widthPx: Int, color: Int) {
        applyStroke(widthPx, ColorStateList.valueOf(color))
    }

    fun setRadius(radiusPx: Float) {
        radiusAdjustBounds = false
        drawable.cornerRadius = radiusPx
    }

    fun setRadii(topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        radiusAdjustBounds = false
        drawable.cornerRadii = floatArrayOf(
            topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft
        )
    }

    /**
     * 自动胶囊：圆角恒为短边的一半。宿主控件在 `onSizeChanged` 里调。
     * 只改圆角，不重建 drawable、不动颜色 —— 见类注释约束 2。
     */
    fun onSizeChanged(width: Int, height: Int) {
        if (!radiusAdjustBounds) return
        drawable.cornerRadius = minOf(width, height) / 2f
    }
}
