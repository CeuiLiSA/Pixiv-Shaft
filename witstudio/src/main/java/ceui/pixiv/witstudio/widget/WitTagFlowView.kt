package ceui.pixiv.witstudio.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import kotlin.math.roundToInt

/**
 * 从 app 的 V3TagFlowView 下沉的标签流。保留换行/单行、译文、折叠动作、输入编辑器，
 * 并提供稳定 key 选择、独立删除和自定义内容。业务导航与菜单由宿主处理。
 *
 * 默认只执行动作；maxSelectCount: 0 = 不选择，1 = 单选，-1 = 不限，其余正数 = 上限。
 * setItems 是数据刷新入口；同内容不会重建，重新排序时选择跟随 key。
 * flexWrap / justifyContent 仍使用 Flexbox 的 XML 属性（包含起始/居中/末端对齐）。
 */
public open class WitTagFlowView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FlexboxLayout(context, attrs, defStyleAttr) {
    public fun interface OnItemClickListener {
        public fun onItemClick(item: WitTagItem, position: Int)
    }
    public fun interface OnItemLongClickListener {
        public fun onItemLongClick(item: WitTagItem, position: Int): Boolean
    }
    public fun interface ItemViewFactory {
        /** 返回内容 View；根节点的点击/长按与主题底由 Flow 管理，内部按钮保留各自监听。 */
        public fun createView(parent: WitTagFlowView, item: WitTagItem, position: Int, palette: V3Palette): View
    }

    private var clickListener: OnItemClickListener? = null
    private var longClickListener: OnItemLongClickListener? = null
    private var removeListener: OnItemClickListener? = null
    private var itemViewFactory: ItemViewFactory? = null
    private var items: List<WitTagItem> = emptyList()
    private var signature: RenderSignature? = null
    private var hasData = false
    private var pendingSelection: Set<String>? = null
    private val selection = linkedSetOf<String>()
    private val itemViews = linkedMapOf<View, WitTagItem>()
    private var input: EditText? = null

    public var onTagClick: ((name: String) -> Unit)? = null
    public var onTagLongClick: ((name: String) -> Unit)? = null
    public var onTagBodyClick: ((name: String) -> Unit)? = null
    public var onSelectionChanged: ((Set<String>) -> Unit)? = null

    public var showRemoveIcon: Boolean = false
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var compact: Boolean = false
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var showTranslation: Boolean = true
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var showHashPrefix: Boolean = true
        set(value) { if (field != value) { field = value; invalidateItems() } }

    /**
     * 原文是否跟随「标签原文亮暗度」增强（[V3Palette.tagLegibilityBoost]）。默认跟随。
     *
     * 只有坐在**宿主自备浅色面**上的标签流才需要关掉它。增强的目标值是按「页面底 + 自身
     * 20% 染色填充」标定的（深色下把原文提亮），浅底上提亮只会让原文更糊：搜索栏的输入区是
     * 硬编码纯白胶囊（`search_et_bg`，无夜间变体），实测输入文字对白底的 APCA 对比度会从
     * 2.73 掉到 1.44。关掉后该处所有颜色回到增强之前的值，**逐位相同**。
     */
    public var followTagLegibilityBoost: Boolean = true
        set(value) { if (field != value) { field = value; invalidateItems() } }

    public var maxTags: Int = -1
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var onOverflowClick: (() -> Unit)? = null
        set(value) {
            val changedRole = (field == null) != (value == null)
            field = value
            if (changedRole) invalidateItems()
        }
    public var overflowActionText: String? = null
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var overflowActionIcon: Int? = null
        set(value) { if (field != value) { field = value; invalidateItems() } }
    public var maxSelectCount: Int = 0
        set(value) {
            require(value >= -1) { "maxSelectCount must be -1, 0 or positive" }
            if (field == value) return
            field = value
            replaceSelection(selection)
            updateSelectionViews()
        }

    public val selectedKeys: Set<String> get() = selection.toSet()
    public val editor: EditText? get() = if (showRemoveIcon) ensureEditor() else null
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()
    private val closeIconSize: Int get() = if (showRemoveIcon) 14.dp else 0
    /** 普通标签的透明上下沿：可见胶囊按内容测量（约 32dp），整颗仍保留 48dp 热区。 */
    private val touchEdge: Int get() = if (compact || showRemoveIcon) 0 else 8.dp

    init { alignItems = AlignItems.FLEX_START }

    public fun setOnItemClickListener(listener: OnItemClickListener?) { clickListener = listener }
    public fun setOnItemLongClickListener(listener: OnItemLongClickListener?) { longClickListener = listener }
    public fun setOnItemRemoveListener(listener: OnItemClickListener?) { removeListener = listener }
    public fun setItemViewFactory(factory: ItemViewFactory?) {
        itemViewFactory = factory
        // Factory 通常闭包到即将提交的数据；不能拿上一批条目调用它。
        signature = null
    }

    public fun setTagNames(names: List<String>) {
        setItems(names.map { WitTagItem(it, it) })
    }

    public open fun setItems(values: List<WitTagItem>) {
        val grew = values.size > items.size
        items = values.toList()
        hasData = true
        val requested = pendingSelection ?: selection.toSet()
        if (items.isNotEmpty()) pendingSelection = null
        replaceSelection(requested)
        renderItems()
        if (grew) (parent as? HorizontalScrollView)?.let { hsv ->
            hsv.post { hsv.fullScroll(if (layoutDirection == LAYOUT_DIRECTION_RTL) FOCUS_LEFT else FOCUS_RIGHT) }
        }
    }

    /** 可在异步数据到达前预选；之后无效 key 被过滤，数量限制同用户点击。 */
    public fun setSelectedKeys(keys: Set<String>) {
        if (!hasData || items.isEmpty()) pendingSelection = keys.toSet() else replaceSelection(keys)
    }

    /** 同一个 Context 原地换主题时由宿主调用；重新挂载和配置变化也会自动刷新。 */
    public fun refreshTheme() { invalidateItems() }

    protected open fun translationColor(palette: V3Palette): Int = palette.textTagAux
    protected open fun onDefaultTagClick(item: WitTagItem): Unit = Unit
    protected open fun onDefaultTagLongClick(item: WitTagItem): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderItems()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTheme()
    }

    private fun invalidateItems() {
        signature = null
        renderItems()
    }

    /**
     * 本视图渲染用的调色板。不跟随增强时把强度**清零**（而不是"少增强一点"）：0 档的值与
     * 增强功能存在之前逐位相同，所以关掉的场合外观零变化。
     */
    private fun paletteForRender(): V3Palette {
        val global = V3Palette.from(context)
        return if (followTagLegibilityBoost) global else V3Palette(global.primary, global.isDark, 0f)
    }

    private fun renderItems() {
        val palette = paletteForRender()
        val translation = translationColor(palette)
        val next = RenderSignature(
            items, palette.primary, palette.isDark, translation, flexWrap, palette.tagLegibilityBoost,
        )
        if (signature == next) return
        signature = next
        // 不 detach 编辑器：保住焦点、反向选区、composing 文本与用户隐藏的键盘。
        for (i in childCount - 1 downTo 0) {
            if (!showRemoveIcon || getChildAt(i) !== input) removeViewAt(i)
        }
        itemViews.clear()
        val visible = if (maxTags > 0) items.take(maxTags) else items
        if (maxTags > 0 && maxLine != -1) maxLine = -1
        visible.forEachIndexed { position, item ->
            val custom = itemViewFactory?.createView(this, item, position, palette)
            val view = custom ?: createItem(item, position, palette, translation)
            val edge = if (custom == null) touchEdge else 0
            view.background = chipBackground(palette, edge)
            view.layoutParams = chipLayoutParams(position == visible.lastIndex, edge)
            if (view !is TextView || !showRemoveIcon) {
                view.setOnClickListener { click(item, position) }
                view.setOnLongClickListener { longClick(item, position) }
                applyTouchScale(view)
            }
            view.isFocusable = true
            view.setAccessibilityDelegate(object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = maxSelectCount != 0
                    info.isChecked = item.key in selection
                }
            })
            itemViews[view] = item
            addView(view)
        }
        val overflowCount = items.size - visible.size
        val actionText = overflowActionText ?: if (overflowCount > 0) "+$overflowCount" else null
        if (actionText != null) {
            val action = newText(actionText, palette).apply {
                setTextColor(if (onOverflowClick != null) palette.textAccent else palette.textSecondary)
                background = chipBackground(palette, touchEdge)
                layoutParams = chipLayoutParams(true, touchEdge)
                if (onOverflowClick != null) {
                    overflowActionIcon?.let { setLeadingIcon(this, it, palette) }
                    setOnClickListener { onOverflowClick?.invoke() }
                    applyTouchScale(this)
                }
            }
            addView(action)
        }
        if (showRemoveIcon) {
            val ed = ensureEditor()
            ed.setTextColor(palette.textTag)
            ed.setHintTextColor(palette.textSecondary)
            if (ed.parent === this) bringChildToFront(ed) else addView(ed)
        }
        updateSelectionViews()
    }

    private fun createItem(item: WitTagItem, position: Int, palette: V3Palette, translationColor: Int): View {
        val suffix = if (showTranslation && !item.translation.isNullOrBlank()) "  ${item.translation}" else ""
        val text = (if (showHashPrefix) "# " else "") + item.name + suffix
        val label = newText(SpannableString(text).apply {
            if (suffix.isNotEmpty()) setSpan(ForegroundColorSpan(translationColor),
                length - suffix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, palette)
        if (item.leadingIcon != 0) setLeadingIcon(label, item.leadingIcon, palette)
        if (item.removeDescription != null) {
            // 历史记录删除是独立按钮；正文搜索和长按菜单不抢它的事件。
            // 删除键与正文同坐一颗胶囊：自身不画底，16dp 图标，48dp 热区落在透明上下沿与胶囊末端。
            label.setPaddingRelative(label.paddingStart, label.paddingTop, 0, label.paddingBottom)
            return LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(context).apply {
                    contentDescription = item.removeDescription
                    setImageResource(R.drawable.wit_ic_tag_close)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // 删除键是"非原文"：用未增强的 textTagAux，拉高辨识度时它不动。
                    imageTintList = ColorStateList.valueOf(palette.textTagAux)
                    background = RippleDrawable(ColorStateList.valueOf(palette.alpha20), null, null)
                        .apply { radius = 18.dp }
                    setPaddingRelative(16.dp, 0, 14.dp, 0)
                    setOnClickListener { removeListener?.onItemClick(item, position) }
                }, LinearLayout.LayoutParams(48.dp, LayoutParams.MATCH_PARENT))
            }
        }
        if (showRemoveIcon) {
            val close = AppCompatResources.getDrawable(context, R.drawable.wit_ic_tag_close)?.mutate()
            close?.setBounds(0, 0, closeIconSize, closeIconSize)
            close?.setTint(palette.textTagAux)
            label.setCompoundDrawablesRelative(null, null, close, null)
            label.compoundDrawablePadding = 4.dp
            label.setPaddingRelative(label.paddingStart, label.paddingTop,
                label.paddingEnd - 4.dp, label.paddingBottom)
            var touchX = -1f
            label.setOnClickListener {
                val x = touchX
                touchX = -1f
                if (onTagBodyClick != null && x >= 0 && !isInRemoveZone(label, x)) {
                    onTagBodyClick?.invoke(item.name)
                } else click(item, position)
            }
            label.setOnLongClickListener {
                touchX = -1f
                longClick(item, position)
            }
            applyTouchScale(label) { touchX = it }
        }
        return label
    }

    private fun newText(text: CharSequence, palette: V3Palette): TextView = TextView(context).apply {
        this.text = text
        textSize = if (compact) 11.5f else 13f
        WitTagStyle.applyText(this, palette)
        setTextColor(palette.textTag)
        // 输入框内的标签沿用按内容测量的高度，避免 48dp 下限撑大整个搜索栏。
        // 紧凑列表同样保持原有密度；普通标签的 48dp 只是热区，多出的部分落在透明上下沿。
        minHeight = if (compact || showRemoveIcon) 0 else 48.dp
        gravity = Gravity.CENTER_VERTICAL
        // 带透明沿的普通标签内边距取 6dp：中文回退字体行高约 20dp，单行正好落在 48dp 热区、32dp 可见胶囊；
        // 换行后文字与胶囊上下仍留 6dp。
        val vertical = when {
            compact -> 4.dp
            touchEdge > 0 -> 6.dp + touchEdge
            else -> 7.dp
        }
        setPaddingRelative(if (compact) 10.dp else 14.dp, vertical,
            if (compact) 10.dp else 14.dp, vertical)
    }

    private fun chipBackground(palette: V3Palette, edge: Int): Drawable {
        val pill = WitTagStyle.background(palette, resources.displayMetrics.density)
        if (edge == 0) return pill
        // 透明沿只属于绘制区域；InsetDrawable 默认把 inset 报成 padding，会覆盖条目自己的内边距，
        // 让历史行在 48dp 内容外再多出 16dp，可见胶囊被撑到 48dp。
        return object : InsetDrawable(pill, 0, edge, 0, edge) {
            override fun getPadding(padding: Rect): Boolean {
                padding.setEmpty()
                return false
            }
        }
    }

    private fun setLeadingIcon(view: TextView, icon: Int, palette: V3Palette) {
        AppCompatResources.getDrawable(context, icon)?.mutate()?.let {
            val size = 16.dp
            it.setBounds(0, 0, size, size)
            it.setTint(palette.textAccent)
            view.setCompoundDrawablesRelative(it, null, null, null)
            view.compoundDrawablePadding = 4.dp
        }
    }

    private fun chipLayoutParams(last: Boolean, edge: Int = 0): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            val gap = if (compact) 6.dp else 8.dp
            marginEnd = if (flexWrap == FlexWrap.NOWRAP && last) 0 else gap
            // 上下透明沿已隔开相邻两行的可见胶囊，行距不再叠加。
            bottomMargin = if (flexWrap == FlexWrap.NOWRAP) 0 else (gap - 2 * edge).coerceAtLeast(0)
            flexShrink = 0f
        }

    private fun click(item: WitTagItem, position: Int) {
        toggleSelection(item.key)
        when {
            clickListener != null -> clickListener?.onItemClick(item, position)
            onTagClick != null -> onTagClick?.invoke(item.name)
            else -> onDefaultTagClick(item)
        }
    }

    private fun longClick(item: WitTagItem, position: Int): Boolean {
        val handled = when {
            longClickListener != null -> longClickListener?.onItemLongClick(item, position) == true
            onTagLongClick != null -> { onTagLongClick?.invoke(item.name); true }
            else -> onDefaultTagLongClick(item)
        }
        // 未消费的长按交还平台；只在一次手势确实被消费时切换，避免随后 click 二次切换。
        if (handled) toggleSelection(item.key)
        return handled
    }

    private fun toggleSelection(key: String) {
        if (maxSelectCount == 0) return
        val next = selection.toMutableSet()
        when {
            !next.add(key) -> next.remove(key)
            maxSelectCount == 1 -> { next.clear(); next.add(key) }
            maxSelectCount > 0 && next.size > maxSelectCount -> return
        }
        replaceSelection(next)
    }

    private fun replaceSelection(requested: Set<String>) {
        val valid = items.mapTo(hashSetOf()) { it.key }
        val next = requested.filter { it in valid }
            .take(if (maxSelectCount < 0) Int.MAX_VALUE else maxSelectCount).toSet()
        val changed = selection != next
        selection.clear()
        selection.addAll(next)
        updateSelectionViews()
        if (changed) onSelectionChanged?.invoke(selectedKeys)
    }

    private fun updateSelectionViews() {
        itemViews.forEach { (view, item) -> view.isActivated = item.key in selection }
    }

    private fun ensureEditor(): EditText {
        input?.let { return it }
        return EditText(context).apply {
            val palette = V3Palette.from(context)
            background = null
            setTextColor(palette.textTag)
            setHintTextColor(palette.textSecondary)
            textSize = 15f
            setPadding(4.dp, 6.dp, 8.dp, 6.dp)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minWidth = 96.dp
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                flexShrink = 0f
                flexGrow = 1f
            }
            input = this
        }
    }

    // 监听器始终返回 false，平台 onTouchEvent 负责 performClick 和完整长按判定。
    @SuppressLint("ClickableViewAccessibility")
    private fun applyTouchScale(view: View, onDown: ((Float) -> Unit)? = null) {
        view.setOnTouchListener { v, event ->
            val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDown?.invoke(event.x)
                    if (animate) v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) onDown?.invoke(-1f)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(if (animate) 150 else 0).start()
                }
            }
            false
        }
    }

    private fun isInRemoveZone(chip: TextView, x: Float): Boolean {
        val zone = closeIconSize + chip.compoundDrawablePadding
        return if (chip.layoutDirection == LAYOUT_DIRECTION_RTL) x <= chip.paddingEnd + zone
            else x >= chip.width - chip.paddingEnd - zone
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState())
        putStringArrayList("selected", ArrayList(pendingSelection ?: selection))
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) { super.onRestoreInstanceState(state); return }
        super.onRestoreInstanceState(state.getParcelable("super"))
        setSelectedKeys(state.getStringArrayList("selected").orEmpty().toSet())
    }

    /**
     * 重绘判定。**必须显式带上 [V3Palette.tagLegibilityBoost]**：译文本月改成不跟随增强
     * （[V3Palette.textTagAux]）之后，签名里已经没有别的字段会随增强变化了 —— 漏掉它，
     * 用户拉滑条时已挂载的标签流不会重绘，原文颜色也就跟着不更新。
     */
    private data class RenderSignature(
        val items: List<WitTagItem>, val primary: Int, val dark: Boolean,
        val translation: Int, val wrap: Int, val boost: Float,
    )
}
