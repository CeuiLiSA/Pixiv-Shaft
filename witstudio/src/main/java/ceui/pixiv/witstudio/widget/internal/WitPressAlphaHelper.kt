package ceui.pixiv.witstudio.widget.internal

import android.view.View

/**
 * 按下 / 禁用时压整体透明度的按压反馈，[ceui.pixiv.witstudio.widget.WitAlphaImageButton] 与
 * [ceui.pixiv.witstudio.widget.WitRoundButton] 共用。
 *
 * 对应 QMUI 的 `QMUIAlphaViewHelper`。两个开关都**默认 true**，和 QMUI 一致 ——
 * `FragmentIllust` 里那三行 `setChangeAlphaWhenPress(true)` 因此是冗余的，但保留 setter
 * 以便调用方原样迁移。
 *
 * 透明度取值对齐 QMUI 主题的 `qmui_alpha_pressed` / `qmui_alpha_disabled`（都是 0.5），
 * 且同样是瞬时切换、不做动画：这是迁移，先保证手感不变。
 */
internal class WitPressAlphaHelper(private val view: View) {

    var changeAlphaWhenPress: Boolean = true
        set(value) {
            field = value
            sync()
        }

    var changeAlphaWhenDisable: Boolean = true
        set(value) {
            field = value
            sync()
        }

    fun sync() {
        view.alpha = when {
            !view.isEnabled -> if (changeAlphaWhenDisable) ALPHA_DISABLED else ALPHA_NORMAL
            // 不可点的控件按下去不该有反馈，否则装饰性图标也会闪一下（同 QMUI 的判定）。
            view.isPressed && view.isClickable && changeAlphaWhenPress -> ALPHA_PRESSED
            else -> ALPHA_NORMAL
        }
    }

    private companion object {
        const val ALPHA_NORMAL = 1.0f
        const val ALPHA_PRESSED = 0.5f
        const val ALPHA_DISABLED = 0.5f
    }
}
