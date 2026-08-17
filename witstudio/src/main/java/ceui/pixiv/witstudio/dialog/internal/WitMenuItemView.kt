package ceui.pixiv.witstudio.dialog.internal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.dialog.WitDialogMetrics
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 弹窗里的一行菜单项，替代 `QMUIDialogMenuItemView` 的三种子类
 * （`TextItemView` / `MarkItemView` / `CheckItemView`）。
 *
 * 合并成一个类而不是三个：三者只差「右边有没有勾」「左边有没有方框」，
 * QMUI 拆成三个类是为了配合它那套 ItemViewFactory 的公开扩展点，
 * 而本项目一处都没用过那个扩展点（`grep QMUIDialogMenuItemView` = 0），所以不必保留。
 */
internal class WitMenuItemView(
    context: Context,
    private val style: Style,
    text: CharSequence,
    private val palette: V3Palette,
) : LinearLayout(context) {

    internal enum class Style { TEXT, MARK, CHECK }

    internal fun interface OnItemClick {
        fun onClick(index: Int)
    }

    var menuIndex: Int = -1
    private var listener: OnItemClick? = null

    private val textView = AppCompatTextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.MENU_ITEM_TEXT_SP)
        setTextColor(ContextCompat.getColor(context, R.color.wit_text_1))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    /** MARK 用右侧的勾，CHECK 用左侧的方框；TEXT 两个都没有。 */
    private val indicator: AppCompatImageView? = when (style) {
        Style.TEXT -> null
        else -> AppCompatImageView(context)
    }

    private var checked: Boolean = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = WitDisplay.dp2px(context, WitDialogMetrics.PADDING_HORIZONTAL_DP)
        setPadding(padH, 0, padH, 0)
        background = rowRipple(context, palette.alpha15)
        isClickable = true
        isFocusable = true

        val size = WitDisplay.dp2px(context, WitDialogMetrics.MENU_MARK_SIZE_DP)
        val space = WitDisplay.dp2px(context, WitDialogMetrics.MENU_MARK_SPACE_DP)
        when (style) {
            Style.TEXT -> {
                addView(textView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            Style.MARK -> {
                addView(textView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(indicator, LayoutParams(size, size).apply { marginStart = space })
            }
            Style.CHECK -> {
                addView(indicator, LayoutParams(size, size).apply { marginEnd = space })
                addView(textView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        syncIndicator()

        setOnClickListener { listener?.onClick(menuIndex) }
    }

    fun setListener(listener: OnItemClick?) {
        this.listener = listener
    }

    var isChecked: Boolean
        get() = checked
        set(value) {
            if (checked == value) return
            checked = value
            syncIndicator()
        }

    private fun syncIndicator() {
        val view = indicator ?: return
        when (style) {
            Style.MARK -> {
                // 未选中不是「隐藏」而是「占位不可见」：否则选中态一变，文字宽度会跟着跳。
                view.setImageResource(R.drawable.wit_ic_dialog_mark)
                view.imageTintList = ColorStateList.valueOf(palette.textAccent)
                view.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            }
            Style.CHECK -> {
                view.setImageResource(
                    if (checked) R.drawable.wit_ic_dialog_checkbox_on
                    else R.drawable.wit_ic_dialog_checkbox_off
                )
                view.imageTintList = ColorStateList.valueOf(
                    if (checked) palette.textAccent
                    else ContextCompat.getColor(context, R.color.wit_text_3)
                )
            }
            Style.TEXT -> Unit
        }
    }
}

/**
 * 整行铺满的方形 ripple。菜单行是全宽可点区，mask 用直角矩形；
 * 卡片圆角的裁切交给 [ceui.pixiv.witstudio.dialog.WitDialogView] 的 clipToOutline。
 */
private fun rowRipple(context: Context, rippleColor: Int): RippleDrawable {
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
}
