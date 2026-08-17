package ceui.pixiv.witstudio.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import ceui.pixiv.witstudio.widget.internal.WitPressAlphaHelper

/**
 * 按下 / 禁用时整体压透明度的图标按钮，替代 `QMUIAlphaImageButton`。
 *
 * 用途是给「背景已经由 `android:background` 画好」的图标按钮加一层按压反馈 ——
 * 和 [WitRoundButton] 不同，本控件**不碰背景**，只改 `alpha`。
 * 行为细节见 [WitPressAlphaHelper]。
 */
public class WitAlphaImageButton @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle,
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    private val alphaHelper = WitPressAlphaHelper(this)

    /** 同 `QMUIAlphaImageButton#setChangeAlphaWhenPress`。默认已是 true。 */
    public fun setChangeAlphaWhenPress(changeAlphaWhenPress: Boolean) {
        alphaHelper.changeAlphaWhenPress = changeAlphaWhenPress
    }

    /** 同 `QMUIAlphaImageButton#setChangeAlphaWhenDisable`。默认已是 true。 */
    public fun setChangeAlphaWhenDisable(changeAlphaWhenDisable: Boolean) {
        alphaHelper.changeAlphaWhenDisable = changeAlphaWhenDisable
    }

    // ⚠️ 父类构造期间就会回调这两个 setter，那时 alphaHelper 还没初始化（类型系统看不出来）。
    // 直接调用会在 inflate 阶段崩，必须空安全。
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
}
