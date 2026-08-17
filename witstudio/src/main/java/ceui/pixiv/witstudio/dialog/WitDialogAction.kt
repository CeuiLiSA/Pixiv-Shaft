package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 弹窗底部的操作按钮，替代 `QMUIDialogAction`。
 *
 * 三个 `ACTION_PROP_*` 的取值与 QMUI 逐位相同（0/1/2），迁移时常量可以直接换名字。
 *
 * **配色规则**（与 QMUI 的差别是有意的）：
 * - `NEUTRAL` / `POSITIVE` → [V3Palette.textAccent]，也就是跟着主题色走。
 *   QMUI 原本写死 `qmui_config_color_blue`（#1B88EE），十档主题切过去也不动 —— 这是升级。
 *   `POSITIVE` 额外加粗一档，保住「主推项」的层次。
 * - `NEGATIVE` → `@color/wit_danger`。本项目里 NEGATIVE 全部落在「删除 / 清除 / 注销」
 *   这类不可逆操作上（32 处），红色是语义色不是强调色，所以不跟主题走。
 *
 * **点击不自动关窗**，与 QMUI 一致 —— 这条语义是承重的：调用方靠「校验不通过就
 * `return@addAction`，弹窗留在原地」来做表单校验。改成自动 dismiss 会静默丢数据。
 */
public class WitDialogAction @JvmOverloads constructor(
    private var text: CharSequence? = null,
    private var listener: ActionListener? = null,
) {

    public companion object {
        public const val ACTION_PROP_POSITIVE: Int = 0
        public const val ACTION_PROP_NEUTRAL: Int = 1
        public const val ACTION_PROP_NEGATIVE: Int = 2
    }

    /** 单方法接口，让 Java 的匿名类和 Kotlin 的尾随 lambda（含 `return@addAction`）都能用。 */
    public fun interface ActionListener {
        public fun onClick(dialog: WitDialog, index: Int)
    }

    public constructor(context: Context, @StringRes strRes: Int) :
            this(context.resources.getText(strRes), null)

    public constructor(context: Context, @StringRes strRes: Int, listener: ActionListener?) :
            this(context.resources.getText(strRes), listener)

    @DrawableRes
    private var iconRes: Int = 0
    private var actionProp: Int = ACTION_PROP_NEUTRAL
    private var enabled: Boolean = true
    private var actionView: AppCompatTextView? = null

    public fun prop(actionProp: Int): WitDialogAction = apply { this.actionProp = actionProp }

    public fun iconRes(@DrawableRes iconRes: Int): WitDialogAction = apply { this.iconRes = iconRes }

    public fun onClick(listener: ActionListener?): WitDialogAction = apply { this.listener = listener }

    public fun setEnabled(enabled: Boolean): WitDialogAction = apply {
        this.enabled = enabled
        actionView?.let {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    public fun getActionProp(): Int = actionProp

    internal fun buildActionView(dialog: WitDialog, index: Int, palette: V3Palette): View {
        val context = dialog.context
        @ColorInt val textColor = when (actionProp) {
            ACTION_PROP_NEGATIVE -> ContextCompat.getColor(context, R.color.wit_danger)
            else -> palette.textAccent
        }
        val button = AppCompatTextView(context).apply {
            text = this@WitDialogAction.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.ACTION_TEXT_SP)
            setTextColor(textColor)
            setTypeface(
                null,
                if (actionProp == ACTION_PROP_POSITIVE) Typeface.BOLD else Typeface.NORMAL
            )
            gravity = Gravity.CENTER
            isSingleLine = true
            minWidth = WitDisplay.dp2px(context, WitDialogMetrics.ACTION_MIN_WIDTH_DP)
            minimumWidth = minWidth
            val padH = WitDisplay.dp2px(context, WitDialogMetrics.ACTION_PADDING_HORIZONTAL_DP)
            setPadding(padH, 0, padH, 0)
            background = pillRipple(context, palette.alpha20, WitDialogMetrics.RADIUS_DP)
            isClickable = true
            isFocusable = true
            isEnabled = this@WitDialogAction.enabled
            alpha = if (this@WitDialogAction.enabled) 1f else DISABLED_ALPHA
        }
        if (iconRes != 0) {
            // 图标跟文字同色：本项目今天没有一处传图标（iconRes 恒为 0），保留只为签名对齐。
            val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
            if (icon != null) {
                DrawableCompat.setTint(icon, textColor)
                icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
                button.setCompoundDrawablesRelative(icon, null, null, null)
                button.compoundDrawablePadding =
                    WitDisplay.dp2px(context, WitDialogMetrics.ACTION_ICON_SPACE_DP)
            }
        }
        button.setOnClickListener {
            if (button.isEnabled) listener?.onClick(dialog, index)
        }
        actionView = button
        return button
    }
}

private const val DISABLED_ALPHA = 0.4f
