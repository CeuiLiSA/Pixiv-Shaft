package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 转圈提示框，替代 `QMUITipDialog`。全 app 的「加载中」都走它（10 处）。
 *
 * **刻意砍掉 `setIconType` 和 `ICON_TYPE_*`**：10 处调用无一例外传的都是 `ICON_TYPE_LOADING`，
 * 保留就是在一个全新的 API 里留一个只有单分支的死枚举。成功/失败提示本项目用 toast，不用这个。
 *
 * 转圈用普通 [ProgressBar] 而不是 Material 圆形进度控件：后者要 M3 的 motion 属性，
 * 在 phase 6 之前的 AppCompat 主题下会 inflate 崩溃，破坏并存期。
 */
public class WitTipDialog @JvmOverloads constructor(
    context: Context,
    @StyleRes styleRes: Int = R.style.ThemeOverlay_Wit_TipDialog,
) : AppCompatDialog(context, styleRes) {

    public class Builder(private val context: Context) {

        private var tipWord: CharSequence? = null

        public fun setTipWord(tipWord: CharSequence?): Builder = apply { this.tipWord = tipWord }

        @JvmOverloads
        public fun create(
            cancelable: Boolean = true,
            @StyleRes style: Int = R.style.ThemeOverlay_Wit_TipDialog,
        ): WitTipDialog {
            val dialog = WitTipDialog(context, style)
            val dialogContext = dialog.context
            val palette = V3Palette.from(dialogContext)

            val container = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = palette.settingsCardBg(
                    WitDisplay.dp2pxF(dialogContext, RADIUS_DP),
                    WitDisplay.hairline(dialogContext),
                )
                val pad = WitDisplay.dp2px(dialogContext, PADDING_DP)
                setPadding(pad, pad, pad, pad)
                minimumWidth = WitDisplay.dp2px(dialogContext, MIN_WIDTH_DP)
            }

            val size = WitDisplay.dp2px(dialogContext, LOADING_SIZE_DP)
            container.addView(
                ProgressBar(dialogContext).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.textAccent)
                },
                LinearLayout.LayoutParams(size, size),
            )

            if (!tipWord.isNullOrEmpty()) {
                container.addView(
                    AppCompatTextView(dialogContext).apply {
                        text = tipWord
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                        setTextColor(ContextCompat.getColor(dialogContext, R.color.wit_text_1))
                        maxWidth = WitDisplay.dp2px(dialogContext, MAX_TEXT_WIDTH_DP)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = WitDisplay.dp2px(dialogContext, TEXT_MARGIN_TOP_DP)
                    },
                )
            }

            dialog.setContentView(container)
            dialog.setCancelable(cancelable)
            dialog.setCanceledOnTouchOutside(false)
            return dialog
        }
    }

    private companion object {
        const val RADIUS_DP = 20f
        const val PADDING_DP = 20
        const val MIN_WIDTH_DP = 120
        const val LOADING_SIZE_DP = 32
        const val TEXT_SP = 13f
        const val TEXT_MARGIN_TOP_DP = 12
        const val MAX_TEXT_WIDTH_DP = 200
    }
}
