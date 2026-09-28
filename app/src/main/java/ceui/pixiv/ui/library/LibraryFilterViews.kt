package ceui.pixiv.ui.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isNotEmpty
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 本地库筛选面板的 V3 + MD3-E 零件：分组、连通按钮组、选择胶囊、开关行、搜索框。
 *
 * 规则照 `docs/v3-design-philosophy.md`「本地库筛选面板」：
 * - **无界平铺**：分组不套卡片，直接铺在 Sheet 底上，靠组标题和留白分层 ——
 *   卡片里再放一个个选项容器，层层嵌套只会让面板变成一堆灰盒子；
 * - **每个选项都是独立容器**，不再放进一条灰轨道 —— 轨道里只浮着一串文字时，
 *   看不出哪里能点、哪几个是一组；
 * - **≤ 4 个互斥档位**是 MD3-E 连通按钮组：等分整行、段间 2dp 缝、外角全圆、内角 8dp；
 *   **更多档位**（排序依据、人气、年份）是胶囊，年份这种长列表单行横滑、贴着屏幕边缘进出；
 * - **选中 = 形状 + 颜色一起变**：未选是 10dp 圆角（连通组是 8dp 内角）的中性底，
 *   选中变全圆胶囊 + 主题浅色容器 + 校正过对比度的主题字色 + 600 字重；胶囊另带勾；
 * - **排除**用日夜 `v3_danger` 的语义色 + 减号，不随主题色变；
 * - 计数是小一号的 `v3_text_2` 次级小标；
 * - 触控热区至少 48dp；状态 180ms 渐变，按下缩到 0.96（系统关动画时不缩）。
 *
 * 颜色全部从 [V3Palette] 运行时派生，十套预设主题和自定义 HEX 自动跟随。
 */
internal class LibraryFilterViews(private val context: Context) {

    private val palette = V3Palette.from(context)

    /** Sheet 底色：所有选项容器直接压在它上面，合成色都以它为底。 */
    private val surface = context.color(R.color.v3_bg)

    /** 未选中项的中性底：`v3_surface_3` 合成在 Sheet 底上（不透明，涟漪与渐变不会透出底色差）。 */
    private val idleFill = ColorUtils.compositeColors(context.color(R.color.v3_surface_3), surface)

    /** 选中态的主题浅色容器（不透明合成）。 */
    private val selectedFill = ColorUtils.compositeColors(palette.alpha20, surface)
    private val selectedText = palette.textTag
    private val idleText = context.color(R.color.v3_text_1)
    private val mutedText = context.color(R.color.v3_text_2)
    private val danger = context.color(R.color.v3_danger)

    // ─────────────────────────── 结构 ───────────────────────────

    /** 分组标题：页内二级标题，比组内行标题高一档，与 Sheet 标题左对齐。 */
    fun groupHeader(text: CharSequence, first: Boolean): TextView =
        context.label(text, 17f, 700, idleText).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            setPaddingRelative(context.dp(EDGE), 0, context.dp(EDGE), 0)
            layoutParams = matchWidth(topMargin = if (first) 8 else 36).also {
                it.bottomMargin = context.dp(14)
            }
        }

    /** 一组：没有背景、没有描边，只是把几行竖着排起来。 */
    fun group(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWidth(topMargin = 0)
    }

    /**
     * 组里的一行：行标题 +（可选的一句说明）+ 控件。行之间 20dp 留白，不画分隔线。
     *
     * 每一行自己带左右 [EDGE] 内边距，而不是由外层容器统一缩进：横滑的胶囊行
     * （[choiceChips] 的 `scroll`）要满宽贴着屏幕边缘进出，靠负边距溢出会被外层的
     * clipToPadding / clipChildren 裁掉。
     */
    fun row(
        group: LinearLayout,
        title: CharSequence?,
        hint: CharSequence? = null,
        control: View,
    ): LinearLayout {
        val edge = context.dp(EDGE)
        val fullBleed = control is HorizontalScrollView
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title?.let {
            row.addView(
                context.label(it, 14f, 600, idleText).apply { setPaddingRelative(edge, 0, edge, 0) },
                matchWidth(topMargin = 0),
            )
        }
        hint?.let {
            row.addView(
                context.label(it, 12f, 400, mutedText).apply {
                    setLineSpacing(0f, 1.3f)
                    setPaddingRelative(edge, 0, edge, 0)
                },
                matchWidth(topMargin = if (title == null) 0 else 4),
            )
        }
        val gap = if (title == null && hint == null) 0 else 10
        row.addView(control, matchWidth(topMargin = gap).also {
            if (!fullBleed) {
                it.marginStart = edge
                it.marginEnd = edge
            }
        })
        group.addView(row, matchWidth(topMargin = if (group.isNotEmpty()) 20 else 0))
        return row
    }

    // ─────────────────────────── 单选 ───────────────────────────

    /** 一组单选控件（连通组或胶囊），对外只暴露「选第几个」与「就地换文案」。 */
    inner class ChoiceGroup internal constructor(
        val view: View,
        private val options: List<TextView>,
        private val withCheck: Boolean,
    ) {
        private var laidOut = false

        fun select(index: Int) {
            options.forEachIndexed { i, option -> option.setChosen(i == index, withCheck) }
            options.getOrNull(index)?.let(::revealInScroll)
        }

        /** 就地换文案（选项个数与顺序不变时用，免得为几个数字重建整组）。 */
        fun setLabels(labels: List<CharSequence>) {
            options.forEachIndexed { i, option -> labels.getOrNull(i)?.let { option.text = it } }
        }

        /** 横滑行里选中项被滑出视野时滚回来；首次布局直接跳到位，之后平滑滚动。 */
        private fun revealInScroll(option: TextView) {
            val scroller = view as? HorizontalScrollView ?: return
            option.doOnLayout {
                val pad = context.dp(EDGE)
                val left = option.left - pad
                val right = option.right + pad - scroller.width
                val target = when {
                    left < scroller.scrollX -> left
                    right > scroller.scrollX -> right
                    else -> null
                }
                if (target != null) {
                    if (laidOut) scroller.smoothScrollTo(target, 0) else scroller.scrollTo(target, 0)
                }
                laidOut = true
            }
        }
    }

    /**
     * MD3-E 连通按钮组：等分整行，段间 2dp 缝，外角全圆、内角 8dp；选中段变成全圆胶囊。
     * 给 ≤ 4 个互斥档位用（分级、AI、页数、排序方向…），一眼读出「这几个里只能选一个」。
     */
    fun connectedGroup(labels: List<CharSequence>, onSelect: (Int) -> Unit): ChoiceGroup {
        val container = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val options = labels.mapIndexed { index, text ->
            segment(text, index, labels.size).also { option ->
                option.setOnClickListener { onSelect(index) }
                container.addView(option, LinearLayout.LayoutParams(0, -2, 1f).also {
                    if (index > 0) it.marginStart = context.dp(SEGMENT_GAP)
                })
            }
        }
        return ChoiceGroup(container, options, withCheck = false)
    }

    /**
     * 单选胶囊：选项多、文案长短不一时用。默认换行排开；[scroll] 为 true 时单行横滑
     * （年份这类会随书架增长的长列表，换行会把卡片撑成一堵墙）。
     */
    fun choiceChips(labels: List<CharSequence>, scroll: Boolean = false, onSelect: (Int) -> Unit): ChoiceGroup {
        val options = labels.mapIndexed { index, text ->
            chip(text).also { it.setOnClickListener { onSelect(index) } }
        }
        val view: View = if (scroll) {
            val strip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dp(EDGE), 0, context.dp(EDGE - CHIP_GAP), 0)
            }
            options.forEach { strip.addView(it, LinearLayout.LayoutParams(-2, -2).also { lp -> lp.marginEnd = context.dp(CHIP_GAP) }) }
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(context.dp(EDGE))
                addView(strip)
            }
        } else {
            newFlow().apply {
                options.forEach { option ->
                    addView(option, FlexboxLayout.LayoutParams(-2, -2).also {
                        it.marginEnd = context.dp(CHIP_GAP)
                        it.flexShrink = 0f
                    })
                }
            }
        }
        return ChoiceGroup(view, options, withCheck = true)
    }

    private fun segment(text: CharSequence, index: Int, total: Int): TextView =
        context.label(text, 14f, 500, idleText).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            maxLines = 2
            val full = context.dpF(20f)
            val inner = context.dpF(8f)
            val start = if (index == 0) full else inner
            val end = if (index == total - 1) full else inner
            val idle = cornered(idleFill, start, end)
            val chosen = shape(full, selectedFill)
            // 可见 40dp，上下各 4dp 透明沿补足 48dp 热区 —— 与 36dp 胶囊同处一张卡时不显得笨重。
            // 背景先设、内边距后设：InsetDrawable 会把 inset 报成 View 的 padding。
            val edge = context.dp(SEGMENT_EDGE)
            background = InsetDrawable(
                RippleDrawable(
                    ColorStateList.valueOf(palette.alpha20),
                    selectedStates(chosen, idle),
                    cornered(Color.WHITE, start, end),
                ),
                0, edge, 0, edge,
            )
            setPadding(context.dp(10), context.dp(6) + edge, context.dp(10), context.dp(6) + edge)
            setTextColor(stateColors(selectedText, idleText))
            isClickable = true
            isFocusable = true
            pressScale()
        }

    // ─────────────────────────── 多选：胶囊 ───────────────────────────

    /** 可多选的筛选胶囊。[count] 是次级小标（标签 / 作者的命中数）。 */
    fun filterChip(text: CharSequence, count: Int? = null, onClick: (TextView) -> Unit): TextView =
        chip(withCount(text, count)).apply {
            setOnClickListener { onClick(this) }
            layoutParams = FlexboxLayout.LayoutParams(-2, -2).also {
                it.marginEnd = context.dp(CHIP_GAP)
                it.flexShrink = 0f
            }
        }

    /**
     * 胶囊的三种状态：未选 / 选中（带勾）/ 排除（危险色，带减号）。
     *
     * 排除态会换掉整张背景，只适合**每次变化都重建**的胶囊（标签云就是这样）；
     * 选中 / 未选之间可以在同一个实例上反复切换。
     */
    fun renderChip(chip: TextView, selected: Boolean, excluded: Boolean = false) {
        chip.setChosen(selected && !excluded, withCheck = true)
        if (excluded) {
            chip.setIcon(R.drawable.ic_remove_circle_outline_black_24dp, danger)
            chip.background = InsetDrawable(
                shape(context.dpF(18f), ColorUtils.setAlphaComponent(danger, 0x24), ColorUtils.setAlphaComponent(danger, 0x66), context.hairlinePx()),
                0, context.dp(CHIP_EDGE), 0, context.dp(CHIP_EDGE),
            )
            chip.setPadding(context.dp(12), context.dp(CHIP_EDGE), context.dp(14), context.dp(CHIP_EDGE))
            chip.setTextColor(danger)
        }
    }

    /**
     * 胶囊本体：可见 36dp，上下各 6dp 透明沿补足 48dp 热区。未选 10dp 圆角，选中变全圆 ——
     * MD3-E 用形状变化说明状态，颜色之外多一条线索。
     */
    private fun chip(text: CharSequence): TextView = context.label(text, 14f, 500, idleText).apply {
        gravity = Gravity.CENTER_VERTICAL
        // 单行必须先于 minHeight：setSingleLine 内部走 setLines(1)，会把最小高度一并改成
        // 「一行」，顺序反了胶囊就塌成文字高度、上下内边距全没了。
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        minHeight = context.dp(48)
        maxWidth = context.resources.displayMetrics.widthPixels - context.dp(CHIP_MAX_WIDTH_INSET)
        val visible = RippleDrawable(
            ColorStateList.valueOf(palette.alpha20),
            selectedStates(shape(context.dpF(18f), selectedFill), shape(context.dpF(10f), idleFill)),
            shape(context.dpF(10f), Color.WHITE),
        )
        // 背景先设、内边距后设：InsetDrawable 会把 inset 报成 View 的 padding，顺序反了就被它覆盖。
        background = InsetDrawable(visible, 0, context.dp(CHIP_EDGE), 0, context.dp(CHIP_EDGE))
        setPadding(context.dp(14), context.dp(CHIP_EDGE), context.dp(14), context.dp(CHIP_EDGE))
        setTextColor(stateColors(selectedText, idleText))
        compoundDrawablePadding = context.dp(6)
        isClickable = true
        isFocusable = true
        pressScale()
    }

    /** 标签云末尾的「显示全部 / 收起」：描边胶囊 + 箭头，和选项区分开，读作一个动作。 */
    fun expandChip(text: CharSequence, expanded: Boolean, onClick: () -> Unit): TextView =
        context.label(text, 14f, 600, palette.textAccent).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = context.dp(48)
            val visible = RippleDrawable(
                ColorStateList.valueOf(palette.alpha20),
                shape(context.dpF(18f), Color.TRANSPARENT, palette.alpha50, context.hairlinePx()),
                shape(context.dpF(18f), Color.WHITE),
            )
            background = InsetDrawable(visible, 0, context.dp(CHIP_EDGE), 0, context.dp(CHIP_EDGE))
            setPadding(context.dp(14), context.dp(CHIP_EDGE), context.dp(10), context.dp(CHIP_EDGE))
            compoundDrawablePadding = context.dp(2)
            val icon = if (expanded) R.drawable.ic_keyboard_arrow_up_black_24dp else R.drawable.ic_baseline_keyboard_arrow_down_24
            ContextCompat.getDrawable(context, icon)?.mutate()?.let { d ->
                d.setTint(palette.textAccent)
                d.setBounds(0, 0, context.dp(20), context.dp(20))
                setCompoundDrawablesRelative(null, null, d, null)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressScale()
            layoutParams = FlexboxLayout.LayoutParams(-2, -2).also { it.flexShrink = 0f }
        }

    // ─────────────────────────── 其它控件 ───────────────────────────

    /**
     * 开关行：标题在左、MD3 开关在右，整行都是热区。
     *
     * 开关用 [MaterialSwitch]（粗轨道、选中时变大的 thumb 带勾），靠一层 Material3 的
     * ContextThemeWrapper 才 inflate 得出来；它只包这一个控件，面板其余部分的 colorPrimary
     * 仍是用户选的主题色，开关自己的颜色也从 [V3Palette] 染回来。
     */
    fun switchRow(title: CharSequence, onToggle: (Boolean) -> Unit): Pair<View, MaterialSwitch> {
        val m3 = ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val switch = MaterialSwitch(m3).apply {
            thumbTintList = ColorStateList(states, intArrayOf(palette.onPrimary, mutedText))
            trackTintList = ColorStateList(states, intArrayOf(palette.primary, idleFill))
            trackDecorationTintList = ColorStateList(states, intArrayOf(Color.TRANSPARENT, mutedText))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            addView(context.label(title, 14f, 600, idleText), LinearLayout.LayoutParams(0, -2, 1f))
            addView(switch, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = context.dp(12) })
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(palette.alpha20), null, shape(context.dpF(12f), Color.WHITE))
            setOnClickListener { onToggle(!switch.isChecked) }
        }
        ViewCompat.setAccessibilityDelegate(row, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = switch.isChecked
                info.className = android.widget.Switch::class.java.name
            }
        })
        return row to switch
    }

    /** 卡片内的搜索框：与选项同一种中性底，全圆胶囊，48dp 高，放大镜在起始侧。 */
    fun searchField(hint: CharSequence): EditText = EditText(context).apply {
        background = shape(context.dpF(24f), idleFill)
        this.hint = hint
        isSingleLine = true
        textSize = 14f
        minHeight = context.dp(48)
        setTextColor(idleText)
        setHintTextColor(mutedText)
        typeface = context.v3Font(500)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_search_black_24dp)?.mutate()?.apply {
            setTint(mutedText)
            setBounds(0, 0, context.dp(20), context.dp(20))
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = context.dp(10)
    }

    /** 卡片内的一句说明（空态 / 规则），`v3_text_2`。 */
    fun hint(text: CharSequence): TextView = context.label(text, 13f, 400, mutedText).apply {
        setLineSpacing(0f, 1.3f)
        layoutParams = matchWidth(topMargin = 8)
    }

    fun newFlow(): FlexboxLayout = FlexboxLayout(context).apply {
        flexWrap = FlexWrap.WRAP
        alignItems = AlignItems.FLEX_START
    }

    /** 底部主操作：整宽实色胶囊，按下时圆角收到 15dp（V3 的形状反馈）。 */
    fun stylePrimaryAction(button: TextView) {
        button.apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(56)
            textSize = 16f
            typeface = context.v3Font(600)
            setTextColor(palette.onPrimary)
            val pressed = shape(context.dpF(15f), palette.primary)
            val idle = shape(context.dpF(28f), palette.primary)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.onPrimary, 0x33)),
                StateListDrawable().apply {
                    setEnterFadeDuration(FADE_MS)
                    setExitFadeDuration(FADE_MS)
                    addState(intArrayOf(android.R.attr.state_pressed), pressed)
                    addState(intArrayOf(), idle)
                },
                null,
            )
            isClickable = true
            isFocusable = true
            pressScale()
        }
    }

    /**
     * 标题行末端的「清空」：有条件时是带重置图标的主题浅色胶囊；没有可清的条件时退成
     * 一行 `v3_text_3` 文字、不可点 —— 不再用 38% 透明的胶囊，那在深色底上只是一块污渍。
     */
    fun renderResetAction(button: TextView, enabled: Boolean) {
        button.apply {
            if (tag != RESET_STYLED) {
                tag = RESET_STYLED
                gravity = Gravity.CENTER
                minHeight = context.dp(48)
                textSize = 14f
                typeface = context.v3Font(600)
                compoundDrawablePadding = context.dp(6)
                pressScale()
            }
            isEnabled = enabled
            val color = if (enabled) palette.textAccent else context.color(R.color.v3_text_3)
            setTextColor(color)
            setIcon(R.drawable.ic_v3_restart, color, sizeDp = 18)
            background = if (enabled) {
                InsetDrawable(
                    RippleDrawable(ColorStateList.valueOf(palette.alpha20), shape(999f, selectedFill), shape(999f, Color.WHITE)),
                    0, context.dp(4), 0, context.dp(4),
                )
            } else {
                null
            }
            setPadding(context.dp(14), context.dp(4), context.dp(16), context.dp(4))
        }
    }

    // ─────────────────────────── 零件 ───────────────────────────

    private fun withCount(text: CharSequence, count: Int?): CharSequence {
        if (count == null) return text
        return SpannableStringBuilder(text).apply {
            append("  ")
            val start = length
            append(BookmarkFilterSheet.formatCount(count))
            setSpan(RelativeSizeSpan(0.86f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(mutedText), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 胶囊文案：主文案 +（可选的）译名（次级色、小一号）+ 命中数（标签 / 作者 / 年份共用）。 */
    fun chipLabel(name: CharSequence, translated: CharSequence? = null, count: Int? = null): CharSequence {
        val text = SpannableStringBuilder(name)
        if (!translated.isNullOrEmpty() && translated != name) {
            text.append("  ")
            val start = text.length
            text.append(translated)
            text.setSpan(RelativeSizeSpan(0.93f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(mutedText), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return withCount(text, count)
    }

    /** 左右两端圆角不同的形状（连通组的首段 / 中段 / 末段）。 */
    private fun cornered(fill: Int, start: Float, end: Float): GradientDrawable {
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val left = if (rtl) end else start
        val right = if (rtl) start else end
        return GradientDrawable().apply {
            cornerRadii = floatArrayOf(left, left, right, right, right, right, left, left)
            setColor(fill)
        }
    }

    private fun selectedStates(selected: Drawable, idle: Drawable): StateListDrawable =
        StateListDrawable().apply {
            setEnterFadeDuration(FADE_MS)
            setExitFadeDuration(FADE_MS)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), idle)
        }

    private fun stateColors(selected: Int, idle: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(selected, idle),
    )

    /** 选中态：背景 / 字色走 state_selected 渐变；字重和勾没法渐变，直接换（初始是未选的 500）。 */
    private fun TextView.setChosen(chosen: Boolean, withCheck: Boolean) {
        if (withCheck) setIcon(if (chosen) R.drawable.ic_check_24dp else null, selectedText)
        if (isSelected == chosen) return
        isSelected = chosen
        typeface = context.v3Font(if (chosen) 600 else 500)
    }

    private fun TextView.setIcon(@DrawableRes icon: Int?, tint: Int, sizeDp: Int = 18) {
        val drawable = icon?.let { ContextCompat.getDrawable(context, it) }?.mutate()?.apply {
            setTint(tint)
            setBounds(0, 0, context.dp(sizeDp), context.dp(sizeDp))
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        // 带图标时起始侧少留一点，让「勾 + 字」整体仍在胶囊里居中
        if (background is InsetDrawable) {
            setPaddingRelative(context.dp(if (drawable != null) 10 else 14), paddingTop, paddingEnd, paddingBottom)
        }
    }

    private fun matchWidth(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).also { it.topMargin = context.dp(topMargin) }

    private companion object {
        /** 内容左右留白（dp），与 Sheet 标题行对齐；横滑行的胶囊条按它缩进首尾。 */
        const val EDGE = 24

        /** 连通组段间缝（dp）。 */
        const val SEGMENT_GAP = 2

        /** 连通组每段上下的透明沿（dp）：40dp 可见 + 2 × 4dp = 48dp 热区。 */
        const val SEGMENT_EDGE = 4

        /** 胶囊之间的横向间距（dp）。 */
        const val CHIP_GAP = 8

        /** 胶囊上下的透明沿（dp）：36dp 可见 + 2 × 6dp = 48dp 热区。 */
        const val CHIP_EDGE = 6

        /** 胶囊最大宽度 = 屏宽减去左右留白（dp），超长标签单行省略。 */
        const val CHIP_MAX_WIDTH_INSET = 48

        const val FADE_MS = 180
        const val RESET_STYLED = "reset_styled"
    }
}
