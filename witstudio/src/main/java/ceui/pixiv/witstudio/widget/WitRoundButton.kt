package ceui.pixiv.witstudio.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.widget.internal.WitPressAlphaHelper
import ceui.pixiv.witstudio.widget.internal.WitRoundHelper

/**
 * 圆角/描边按钮，替代 `QMUIRoundButton`。
 *
 * 默认 `wit_isRadiusAdjustBounds = true`（自动胶囊），与 QMUI 的 `QMUI.RoundButton` 样式一致 ——
 * 关注按钮那种「布局里没写 radius 却是胶囊形」靠的就是这个默认值。
 *
 * ⚠️ 本控件会用自己的圆角背景**覆盖** `android:background`（同 QMUI 的行为）。
 * 在布局里同时写 `android:background` 和 `wit_*` 属性时，前者不会生效。
 */
public class WitRoundButton @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : AppCompatButton(context, attrs, defStyleAttr), WitRoundView {

    private val helper = WitRoundHelper(
        this, context, attrs, defStyleAttr,
        defaultRadiusAdjustBounds = true,
        defaultBorderWidthPx = context.resources
            .getDimensionPixelSize(R.dimen.wit_round_btn_border_width),
    )

    /**
     * QMUIRoundButton 继承自 QMUIAlphaButton，所以它**也带按压变透明** ——
     * 不是只有 [WitAlphaImageButton] 才有。`FragmentIllust` 的下载按钮就依赖这个行为，
     * 漏掉它不会编译报错，只会让按压反馈无声消失。
     */
    private val alphaHelper = WitPressAlphaHelper(this)

    init {
        // 复刻 QMUI.RoundButton 样式里那几条**影响尺寸**的设定。
        //
        // 不复刻的话按钮会悄悄变宽：本控件的 defStyleAttr 是 android.R.attr.buttonStyle，
        // 继承到 Widget.AppCompat.Button 的 minWidth=88dp / minHeight=48dp，
        // 于是 wrap_content 的关注按钮会被撑到 88dp 宽（实测 127px → 190px）。
        // QMUI 是靠样式把这两条按成 0 的，这里只能在代码里补。
        //
        // hasValue 判定保证「布局里显式写了就听布局的」，和样式默认值的优先级语义一致。
        val a = context.obtainStyledAttributes(
            attrs, intArrayOf(android.R.attr.minWidth, android.R.attr.minHeight)
        )
        try {
            if (!a.hasValue(0)) {
                minWidth = 0
                minimumWidth = 0
            }
            if (!a.hasValue(1)) {
                minHeight = 0
                minimumHeight = 0
            }
        } finally {
            a.recycle()
        }
        // 同 QMUI 的 Button.Compat：去掉 5.0+ 按钮按下时的抬升动画，
        // 否则圆角背景会跟着投影一起浮起来，和 V3 近乎零 elevation 的语汇冲突。
        stateListAnimator = null
    }

    /** 同 `QMUIRoundButton#setChangeAlphaWhenPress`。默认已是 true。 */
    public fun setChangeAlphaWhenPress(changeAlphaWhenPress: Boolean) {
        alphaHelper.changeAlphaWhenPress = changeAlphaWhenPress
    }

    /** 同 `QMUIRoundButton#setChangeAlphaWhenDisable`。默认已是 true。 */
    public fun setChangeAlphaWhenDisable(changeAlphaWhenDisable: Boolean) {
        alphaHelper.changeAlphaWhenDisable = changeAlphaWhenDisable
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        helper.onSizeChanged(w, h)
    }

    // ⚠️ 这两个 setter 在**父类构造期间**就会被回调（TextView 解析 android:enabled 时），
    // 那一刻 Kotlin 的属性初始化器还没跑，alphaHelper 实际是 null —— 类型系统看不出来。
    // 直接调用会在 inflate 阶段崩，所以这里必须空安全。
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alphaHelper?.sync()
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alphaHelper?.sync()
    }

    override fun setWitBackgroundColor(color: Int): Unit = helper.setBackgroundColor(color)

    override fun setWitBorder(widthPx: Int, color: Int): Unit = helper.setBorder(widthPx, color)

    override fun setWitRadius(radiusPx: Float): Unit = helper.setRadius(radiusPx)

    override fun setWitRadii(
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
    ): Unit = helper.setRadii(topLeft, topRight, bottomRight, bottomLeft)
}
