package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.text.method.TransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.dialog.internal.WitMenuItemView
import ceui.pixiv.witstudio.theme.WitDisplay
import java.util.BitSet

/**
 * V3 / MD3-E 语汇的弹窗，替代 `QMUIDialog`。
 *
 * 嵌套 builder 用**完全相同的简单名**（`MessageDialogBuilder`、`MenuDialogBuilder`…）和
 * 完全相同的语义，所以 146 处调用点的迁移是 `s/QMUIDialog/WitDialog/` + 改 import，
 * 多出来的任何改动都该被当成 bug 看待。
 *
 * 视觉上与 QMUI 的差别（有意为之）：
 * - 卡片 28dp 圆角 + hairline，底色由 `V3Palette.settingsCardBg` 运行时派生 → 跟随十档主题与自定义 HEX。
 * - 按钮不再是写死的 #1B88EE，见 [WitDialogAction] 的配色说明。
 * - 日夜两套值来自模块自带的 `wit_*`（含 `values-night`），不依赖任何 host 主题属性，
 *   只读 `?attr/colorPrimary` 一个 —— 这就是它能同时活在旧 AppCompat 主题和 phase 6 之后的 M3 主题下的原因。
 */
public class WitDialog @JvmOverloads constructor(
    context: Context,
    @StyleRes styleRes: Int = R.style.ThemeOverlay_Wit_Dialog,
) : AppCompatDialog(context, styleRes) {

    // ── Message ─────────────────────────────────────────────────────

    /** 标题 + 正文的普通弹窗。本项目里用得最多的一种（83 处）。 */
    public open class MessageDialogBuilder(context: Context) :
        WitDialogBuilder<MessageDialogBuilder>(context) {

        protected var mMessage: CharSequence? = null

        public fun setMessage(message: CharSequence?): MessageDialogBuilder =
            apply { mMessage = message }

        public fun setMessage(@StringRes resId: Int): MessageDialogBuilder =
            setMessage(context.resources.getString(resId))

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            if (mMessage.isNullOrEmpty()) return null
            val textView = AppCompatTextView(context).apply {
                text = mMessage
                setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.MESSAGE_TEXT_SP)
                setTextColor(ContextCompat.getColor(context, R.color.wit_text_2))
                setLineSpacing(
                    WitDisplay.dp2pxF(context, WitDialogMetrics.MESSAGE_LINE_SPACING_DP), 1f
                )
                val padH = WitDisplay.dp2px(context, WitDialogMetrics.PADDING_HORIZONTAL_DP)
                val padTop = WitDisplay.dp2px(
                    context,
                    if (hasTitle()) WitDialogMetrics.MESSAGE_PADDING_TOP_DP
                    else WitDialogMetrics.MESSAGE_PADDING_TOP_WHEN_NO_TITLE_DP,
                )
                setPadding(
                    padH,
                    padTop,
                    padH,
                    WitDisplay.dp2px(context, WitDialogMetrics.MESSAGE_PADDING_BOTTOM_DP),
                )
            }
            return wrapWithScroll(textView)
        }
    }

    // ── CheckBoxMessage ─────────────────────────────────────────────

    /** 正文左侧带一个复选框的弹窗（「清除缓存」「注销」这类「下次不再提示」用）。 */
    public open class CheckBoxMessageDialogBuilder(context: Context) :
        WitDialogBuilder<CheckBoxMessageDialogBuilder>(context) {

        protected var mMessage: CharSequence? = null
        private var checked: Boolean = false
        private var checkBoxView: AppCompatImageView? = null

        public fun setMessage(message: String?): CheckBoxMessageDialogBuilder =
            apply { mMessage = message }

        public fun setMessage(@StringRes resId: Int): CheckBoxMessageDialogBuilder =
            setMessage(context.resources.getString(resId))

        public fun isChecked(): Boolean = checked

        public fun setChecked(checked: Boolean): CheckBoxMessageDialogBuilder = apply {
            this.checked = checked
            syncCheckBox()
        }

        private fun syncCheckBox() {
            val view = checkBoxView ?: return
            view.setImageResource(
                if (checked) R.drawable.wit_ic_dialog_checkbox_on
                else R.drawable.wit_ic_dialog_checkbox_off
            )
            view.imageTintList = android.content.res.ColorStateList.valueOf(
                if (checked) palette.textAccent
                else ContextCompat.getColor(view.context, R.color.wit_text_3)
            )
        }

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = WitDisplay.dp2px(context, WitDialogMetrics.PADDING_HORIZONTAL_DP)
                setPadding(
                    padH,
                    WitDisplay.dp2px(
                        context,
                        if (hasTitle()) WitDialogMetrics.MESSAGE_PADDING_TOP_DP
                        else WitDialogMetrics.MESSAGE_PADDING_TOP_WHEN_NO_TITLE_DP,
                    ),
                    padH,
                    WitDisplay.dp2px(context, WitDialogMetrics.MESSAGE_PADDING_BOTTOM_DP),
                )
                isClickable = true
            }
            val size = WitDisplay.dp2px(context, WitDialogMetrics.CHECKBOX_SIZE_DP)
            val box = AppCompatImageView(context)
            checkBoxView = box
            row.addView(
                box,
                LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = WitDisplay.dp2px(context, WitDialogMetrics.CHECKBOX_SPACE_DP)
                },
            )
            row.addView(
                AppCompatTextView(context).apply {
                    text = mMessage
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.MESSAGE_TEXT_SP)
                    setTextColor(ContextCompat.getColor(context, R.color.wit_text_2))
                    setLineSpacing(
                        WitDisplay.dp2pxF(context, WitDialogMetrics.MESSAGE_LINE_SPACING_DP), 1f
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            syncCheckBox()
            // 整行可点，不要求用户精准点中 22dp 的小方框。
            row.setOnClickListener { setChecked(!checked) }
            return wrapWithScroll(row)
        }
    }

    // ── EditText ────────────────────────────────────────────────────

    /** 带单行输入框的弹窗。`getEditText()` 是调用方读回内容的唯一路径，必须非空。 */
    public open class EditTextDialogBuilder(context: Context) :
        WitDialogBuilder<EditTextDialogBuilder>(context) {

        /**
         * 刻意在构造时就建好而不是等到 `onCreateContent`：QMUI 那边 `getEditText()` 在 create 前
         * 返回 null，调用方全都靠「create 之后再读」的时序默契活着。这里直接消灭这个空档，
         * 于是迁移时 `builder.getEditText()` 是纯改名，不用在每个调用点补 `?.`。
         */
        private val mEditText: AppCompatEditText = AppCompatEditText(context)

        private var mRightImageView: AppCompatImageView? = null

        public fun getEditText(): EditText = mEditText

        public fun getRightImageView(): AppCompatImageView? = mRightImageView

        public fun setPlaceholder(placeholder: String?): EditTextDialogBuilder =
            apply { mEditText.hint = placeholder }

        public fun setPlaceholder(@StringRes resId: Int): EditTextDialogBuilder =
            setPlaceholder(context.resources.getString(resId))

        public fun setDefaultText(defaultText: CharSequence?): EditTextDialogBuilder = apply {
            if (!defaultText.isNullOrEmpty()) {
                mEditText.setText(defaultText)
                mEditText.setSelection(mEditText.text?.length ?: 0)
            }
        }

        public fun setInputType(inputType: Int): EditTextDialogBuilder =
            apply { mEditText.inputType = inputType }

        public fun setTransformationMethod(
            transformationMethod: TransformationMethod?,
        ): EditTextDialogBuilder = apply {
            mEditText.transformationMethod = transformationMethod
        }

        public fun setTextWatcher(textWatcher: android.text.TextWatcher?): EditTextDialogBuilder =
            apply { if (textWatcher != null) mEditText.addTextChangedListener(textWatcher) }

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View {
            val padH = WitDisplay.dp2px(context, WitDialogMetrics.EDIT_PADDING_HORIZONTAL_DP)
            mEditText.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.EDIT_TEXT_SP)
                setTextColor(ContextCompat.getColor(context, R.color.wit_text_1))
                setHintTextColor(ContextCompat.getColor(context, R.color.wit_text_3))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = WitDisplay.dp2px(context, WitDialogMetrics.EDIT_MIN_HEIGHT_DP)
                // 输入框是实心圆角块 + hairline，不是 AppCompat 那条下划线 ——
                // 下划线的颜色来自 host 的 colorControlActivated，phase 5/6 会变，不能依赖。
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = WitDisplay.dp2pxF(context, WitDialogMetrics.EDIT_RADIUS_DP)
                    setColor(ContextCompat.getColor(context, R.color.wit_surface_2))
                    setStroke(WitDisplay.hairline(context), palette.cardHairline)
                }
                setPadding(padH, 0, padH, 0)
                // 光标 / 选中高亮跟主题色走。
                highlightColor = palette.alpha30
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val outerPadH = WitDisplay.dp2px(context, WitDialogMetrics.PADDING_HORIZONTAL_DP)
                setPadding(
                    outerPadH,
                    WitDisplay.dp2px(context, WitDialogMetrics.EDIT_MARGIN_TOP_DP),
                    outerPadH,
                    WitDisplay.dp2px(context, WitDialogMetrics.EDIT_MARGIN_BOTTOM_DP),
                )
            }
            (mEditText.parent as? ViewGroup)?.removeView(mEditText)
            container.addView(
                mEditText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            return container
        }

        override fun onAfterCreate(
            dialog: WitDialog,
            rootLayout: WitDialogRootLayout,
            context: Context,
        ) {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            mEditText.requestFocus()
            // 收键盘挂在 detach 而不是 dialog.setOnDismissListener 上：后者只有一个槽位，
            // 占掉了调用方就没法再设自己的 dismiss 回调。
            mEditText.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as? InputMethodManager
                        imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    }
                }
            )
        }
    }

    // ── Menu 家族 ───────────────────────────────────────────────────

    /**
     * 菜单类弹窗的共同基类。
     *
     * 与 QMUI 的差别：不暴露 `ItemViewFactory` / `QMUIDialogMenuItemView` 那套自定义行视图的扩展点
     * —— 全仓一处都没用过（grep = 0），保留只会把内部视图结构变成公开契约。
     */
    public abstract class MenuBaseDialogBuilder<T : MenuBaseDialogBuilder<T>> internal constructor(
        context: Context,
        private val itemStyle: WitMenuItemView.Style,
    ) : WitDialogBuilder<T>(context) {

        private val items = mutableListOf<Pair<CharSequence, DialogInterface.OnClickListener?>>()
        private val itemViews = mutableListOf<WitMenuItemView>()

        @Suppress("UNCHECKED_CAST")
        private fun selfMenu(): T = this as T

        public fun clear(): Unit = items.clear()

        public open fun addItem(
            item: CharSequence,
            listener: DialogInterface.OnClickListener?,
        ): T = selfMenu().also { items.add(item to listener) }

        public open fun addItems(
            items: Array<out CharSequence>,
            listener: DialogInterface.OnClickListener?,
        ): T = selfMenu().also { items.forEach { addItem(it, listener) } }

        /** 子类在这里更新选中态。**注意不 dismiss**，与 QMUI 一致 —— 关窗由 listener 自己决定。 */
        protected open fun onItemClick(index: Int) {}

        // 行视图本身是模块内部结构，不外泄给子类；子类只通过下面三个不带内部类型的接口读写选中态。
        protected fun itemCount(): Int = itemViews.size

        protected fun isItemChecked(index: Int): Boolean =
            itemViews.getOrNull(index)?.isChecked == true

        protected fun setItemChecked(index: Int, checked: Boolean) {
            itemViews.getOrNull(index)?.isChecked = checked
        }

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val padTop = WitDisplay.dp2px(
                context,
                if (hasTitle()) WitDialogMetrics.MENU_CONTAINER_PADDING_TOP_WHEN_TITLE_DP
                else WitDialogMetrics.MENU_CONTAINER_PADDING_VERTICAL_DP,
            )
            val padBottom = WitDisplay.dp2px(
                context,
                if (mActions.isNotEmpty()) WitDialogMetrics.MENU_CONTAINER_PADDING_BOTTOM_WHEN_ACTION_DP
                else WitDialogMetrics.MENU_CONTAINER_PADDING_VERTICAL_DP,
            )
            layout.setPadding(0, padTop, 0, padBottom)

            itemViews.clear()
            val itemHeight = WitDisplay.dp2px(context, WitDialogMetrics.MENU_ITEM_HEIGHT_DP)
            items.forEachIndexed { index, (text, listener) ->
                val itemView = WitMenuItemView(context, itemStyle, text, palette)
                itemView.menuIndex = index
                itemView.setListener { clickedIndex ->
                    onItemClick(clickedIndex)
                    listener?.onClick(dialog, clickedIndex)
                }
                layout.addView(
                    itemView,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight),
                )
                itemViews.add(itemView)
            }
            return wrapWithScroll(layout)
        }
    }

    /** 纯菜单：一列可点条目，点击回调自己决定要不要 dismiss。 */
    public open class MenuDialogBuilder(context: Context) :
        MenuBaseDialogBuilder<MenuDialogBuilder>(context, WitMenuItemView.Style.TEXT)

    /** 单选：选中项右侧打勾。 */
    public open class CheckableDialogBuilder(context: Context) :
        MenuBaseDialogBuilder<CheckableDialogBuilder>(context, WitMenuItemView.Style.MARK) {

        private var mCheckedIndex: Int = -1

        public fun getCheckedIndex(): Int = mCheckedIndex

        public fun setCheckedIndex(checkedIndex: Int): CheckableDialogBuilder =
            apply { mCheckedIndex = checkedIndex }

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            val result = super.onCreateContent(dialog, parent, context)
            if (mCheckedIndex in 0 until itemCount()) setItemChecked(mCheckedIndex, true)
            return result
        }

        override fun onItemClick(index: Int) {
            for (i in 0 until itemCount()) setItemChecked(i, i == index)
            mCheckedIndex = index
        }
    }

    /** 多选：每行左侧一个复选框。 */
    public open class MultiCheckableDialogBuilder(context: Context) :
        MenuBaseDialogBuilder<MultiCheckableDialogBuilder>(context, WitMenuItemView.Style.CHECK) {

        private val mCheckedItems = BitSet()

        public fun setCheckedItems(checkedItems: BitSet?): MultiCheckableDialogBuilder = apply {
            mCheckedItems.clear()
            if (checkedItems != null) mCheckedItems.or(checkedItems)
        }

        public fun setCheckedItems(checkedIndexes: IntArray?): MultiCheckableDialogBuilder = apply {
            mCheckedItems.clear()
            checkedIndexes?.forEach { mCheckedItems.set(it) }
        }

        public fun getCheckedItemRecord(): BitSet = mCheckedItems.clone() as BitSet

        /** 返回被选中的下标数组，如选中第 1、3 项则返回 `[1, 3]`。 */
        public fun getCheckedItemIndexes(): IntArray =
            (0 until itemCount()).filter { isItemChecked(it) }.toIntArray()

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            val result = super.onCreateContent(dialog, parent, context)
            for (i in 0 until itemCount()) setItemChecked(i, mCheckedItems.get(i))
            return result
        }

        override fun onItemClick(index: Int) {
            val next = !isItemChecked(index)
            setItemChecked(index, next)
            mCheckedItems.set(index, next)
        }
    }

    // ── Custom ──────────────────────────────────────────────────────

    /** 内容区完全自定义：要么 `setLayout(...)`，要么覆写 `onCreateContent`。 */
    public open class CustomDialogBuilder(context: Context) :
        WitDialogBuilder<CustomDialogBuilder>(context) {

        @LayoutRes
        private var layoutId: Int = 0

        public fun setLayout(@LayoutRes layoutResId: Int): CustomDialogBuilder =
            apply { layoutId = layoutResId }

        override fun onCreateContent(
            dialog: WitDialog,
            parent: WitDialogView,
            context: Context,
        ): View? {
            if (layoutId == 0) return null
            return LayoutInflater.from(context).inflate(layoutId, parent, false)
        }
    }
}
