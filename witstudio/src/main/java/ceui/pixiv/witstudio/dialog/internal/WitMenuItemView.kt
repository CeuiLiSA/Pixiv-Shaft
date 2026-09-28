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

    /**
     * 行下方的第二行说明（加速地址连通性 / 延迟这类实时状态）。
     *
     * 默认 GONE：不收状态时这一行的渲染与「从来没有这个 View」完全一致 ——
     * 52dp 最小行高、文字纵向居中、右侧勾的位置都不变，所以既有的纯菜单调用点零影响。
     */
    private val statusView = AppCompatTextView(context).apply {
        visibility = GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.MENU_ITEM_STATUS_TEXT_SP)
        setTextColor(ContextCompat.getColor(context, R.color.wit_text_3))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    /** 标题 + 状态的竖排文字列；两种文字都在这一列里，行内只剩「勾 / 正文字列 / 勾」三段。 */
    private val textColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            textView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            statusView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = WitDisplay.dp2px(context, WitDialogMetrics.MENU_ITEM_STATUS_SPACE_DP) },
        )
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
                addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            Style.MARK -> {
                addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(indicator, LayoutParams(size, size).apply { marginStart = space })
            }
            Style.CHECK -> {
                addView(indicator, LayoutParams(size, size).apply { marginEnd = space })
                addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        syncIndicator()

        setOnClickListener { listener?.onClick(menuIndex) }
    }

    /**
     * 设置 / 清空副标题。传 null 或空串即收起（行高回到单行）。
     *
     * [colorInt] 为 null 时用默认的 `wit_text_3`；调用方要给「可达 / 不可达」上色就传自己的语义色。
     */
    fun setStatus(text: CharSequence?, colorInt: Int?) {
        if (text.isNullOrEmpty()) {
            // 连文字一起清掉，不只是 GONE：GONE 的 View 不渲染，但留着上一轮的状态文本，
            // 「这行现在有没有副标题」在视图树里就读不出来了（复用时会被旧文字误导）。
            statusView.text = null
            statusView.visibility = GONE
            return
        }
        statusView.text = text
        statusView.setTextColor(colorInt ?: ContextCompat.getColor(context, R.color.wit_text_3))
        statusView.visibility = VISIBLE
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
