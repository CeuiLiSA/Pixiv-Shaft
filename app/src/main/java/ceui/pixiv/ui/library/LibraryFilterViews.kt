package ceui.pixiv.ui.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
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

/**
 * 本地库筛选面板的 V3 零件：分组卡、连通选择组、筛选胶囊、开关行、搜索框。
 *
 * 规则照 `docs/v3-design-philosophy.md`「按钮、选择器与图标」：
 * - **单选**是浅底连通组（`.v3-filters` 的原生版）：中性轨道里放选项，选中项是独立的主题浅色容器
 *   （20% 主色合成在卡片底上）+ 校正过对比度的主题字色 + 600 字重；
 * - **多选**是独立胶囊：未选中是中性浅底，选中换成同一种主题浅色容器并在前面带一个勾 ——
 *   勾是「可以再选一个」的信号，单选组里没有它；
 * - **排除**用日夜 `v3_danger` 的语义色，不随主题色变（它说的是「不要」，不是「选中」）；
 * - 计数是次级小标：小一号、`v3_text_2`；
 * - 触控热区至少 48dp：胶囊的可见高度 36dp，上下各 6dp 透明沿补足热区；
 * - 选中态颜色 180ms 渐变，按下缩到 0.96（系统关动画时不缩）。
 *
 * 颜色全部从 [V3Palette] 运行时派生，十套预设主题和自定义 HEX 自动跟随。
 */
internal class LibraryFilterViews(private val context: Context) {

    private val palette = V3Palette.from(context)

    /** 选中态的主题浅色容器（不透明合成，字色按它校正）。 */
    private val selectedFill = ColorUtils.compositeColors(palette.alpha20, palette.cardFill)
    private val selectedText = palette.textTag
    private val idleText = context.color(R.color.v3_text_1)
    private val mutedText = context.color(R.color.v3_text_2)
    private val danger = context.color(R.color.v3_danger)

    // ─────────────────────────── 结构 ───────────────────────────

    /** 卡片外的分组标题：比卡内行标题弱一档，只负责把卡片分成几块。 */
    fun groupHeader(text: CharSequence, first: Boolean): TextView =
        context.label(text, 13f, 700, mutedText).apply {
            letterSpacing = .04f
            ViewCompat.setAccessibilityHeading(this, true)
            layoutParams = matchWidth(topMargin = if (first) 8 else 24).also {
                it.marginStart = context.dp(8)
                it.bottomMargin = context.dp(8)
            }
        }

    /** 22dp 分组卡：cardFill + 12% hairline，里面按行排。 */
    fun groupCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.card(22)
        setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(16))
        layoutParams = matchWidth(topMargin = 0)
    }

    /**
     * 卡片里的一行：行标题 +（可选的一句说明）+ 控件。行与行之间一条 hairline，
     * 比每行一张小卡轻，又比纯留白好扫读。
     */
    fun row(
        card: LinearLayout,
        title: CharSequence?,
        hint: CharSequence? = null,
        control: View,
        divider: Boolean = true,
    ): LinearLayout {
        if (divider && card.isNotEmpty()) {
            card.addView(View(context).apply {
                setBackgroundColor(palette.cardHairline)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, context.hairlinePx()).also { it.topMargin = context.dp(12) })
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title?.let {
            row.addView(
                context.label(it, 15f, 600, idleText),
                matchWidth(topMargin = 14),
            )
        }
        hint?.let {
            row.addView(
                context.label(it, 12f, 400, mutedText).apply { setLineSpacing(0f, 1.3f) },
                matchWidth(topMargin = 4),
            )
        }
        row.addView(control, matchWidth(topMargin = if (title == null && hint == null) 12 else 10))
        card.addView(row, matchWidth(topMargin = 0))
        return row
    }

    // ─────────────────────────── 单选：连通组 ───────────────────────────

    /**
     * 连通选择组。选项不多（≤ [EQUAL_SEGMENT_LIMIT]）时等分整行 —— 分级、AI 这类互斥档位
     * 一眼看出「只能选一个」；选项多（排序、年份、人气档）时在同一条轨道里换行排开。
     */
    fun segmentedGroup(labels: List<CharSequence>, onSelect: (Int) -> Unit): SegmentedGroup {
        val equal = labels.size <= EQUAL_SEGMENT_LIMIT
        val trackPad = context.dp(4)
        val container: ViewGroup = if (equal) {
            LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        } else {
            newFlow()
        }
        container.background = shape(context.dpF(24f), context.color(R.color.v3_surface_2))
        container.setPadding(trackPad, trackPad, trackPad, trackPad)
        val options = labels.mapIndexed { index, text ->
            segment(text).also { option ->
                option.setOnClickListener { onSelect(index) }
                if (equal) {
                    container.addView(option, LinearLayout.LayoutParams(0, -2, 1f))
                } else {
                    container.addView(option, FlexboxLayout.LayoutParams(-2, -2).also { it.flexShrink = 0f })
                }
            }
        }
        return SegmentedGroup(container, options)
    }

    inner class SegmentedGroup(val view: ViewGroup, private val options: List<TextView>) {
        fun select(index: Int) {
            options.forEachIndexed { i, option -> option.setChosen(i == index) }
        }
    }

    private fun segment(text: CharSequence): TextView = context.label(text, 14f, 500, idleText).apply {
        gravity = Gravity.CENTER
        minHeight = context.dp(40)
        maxLines = 2
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        val radius = context.dpF(20f)
        background = RippleDrawable(
            ColorStateList.valueOf(palette.alpha20),
            selectedStates(shape(radius, selectedFill, palette.alpha30, context.hairlinePx())),
            shape(radius, Color.WHITE),
        )
        setTextColor(stateColors(selectedText, mutedText))
        isClickable = true
        isFocusable = true
        pressScale()
    }

    // ─────────────────────────── 多选：胶囊 ───────────────────────────

    /** 可多选的筛选胶囊。[count] 是次级小标（标签 / 作者 / 年份的命中数）。 */
    fun filterChip(text: CharSequence, count: Int? = null, onClick: (TextView) -> Unit): TextView =
        context.label(withCount(text, count), 14f, 500, idleText).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = context.dp(48)
            val radius = context.dpF(18f)
            val visible = RippleDrawable(
                ColorStateList.valueOf(palette.alpha20),
                selectedStates(
                    shape(radius, selectedFill, palette.alpha30, context.hairlinePx()),
                    idle = shape(radius, context.color(R.color.v3_surface_2)),
                ),
                shape(radius, Color.WHITE),
            )
            // 可见胶囊 36dp，上下 6dp 透明沿补足 48dp 热区。
            // 背景先设、内边距后设：InsetDrawable 会把 inset 报成 View 的 padding，顺序反了就被它覆盖。
            background = InsetDrawable(visible, 0, context.dp(6), 0, context.dp(6))
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            setTextColor(stateColors(selectedText, idleText))
            compoundDrawablePadding = context.dp(6)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
            pressScale()
            layoutParams = FlexboxLayout.LayoutParams(-2, -2).also {
                it.marginEnd = context.dp(8)
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
        chip.setChosen(selected && !excluded)
        val icon = when {
            excluded -> R.drawable.ic_remove_circle_outline_black_24dp
            selected -> R.drawable.ic_check_24dp
            else -> null
        }
        val tint = if (excluded) danger else selectedText
        val drawable = icon?.let { ContextCompat.getDrawable(context, it) }?.mutate()?.apply {
            setTint(tint)
            setBounds(0, 0, context.dp(16), context.dp(16))
        }
        chip.setCompoundDrawablesRelative(drawable, null, null, null)
        if (excluded) {
            val radius = context.dpF(18f)
            chip.background = InsetDrawable(
                shape(radius, ColorUtils.setAlphaComponent(danger, 0x24), ColorUtils.setAlphaComponent(danger, 0x66), context.hairlinePx()),
                0, context.dp(6), 0, context.dp(6),
            )
            chip.setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            chip.setTextColor(danger)
        }
    }

    // ─────────────────────────── 其它控件 ───────────────────────────

    /** 开关行：标题在左、开关在右，整行都是热区。 */
    fun switchRow(title: CharSequence, onToggle: (Boolean) -> Unit): Pair<View, SwitchCompat> {
        val switch = SwitchCompat(context).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.primary, context.color(R.color.v3_bg)),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.alpha50, context.color(R.color.v3_border_2)),
            )
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            addView(context.label(title, 15f, 600, idleText), LinearLayout.LayoutParams(0, -2, 1f))
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
                info.className = SwitchCompat::class.java.name
            }
        })
        return row to switch
    }

    /** 卡片内的搜索框：中性浅底 16dp 圆角，48dp 高，放大镜在起始侧。 */
    fun searchField(hint: CharSequence): EditText = EditText(context).apply {
        background = shape(context.dpF(16f), context.color(R.color.v3_surface_2))
        this.hint = hint
        isSingleLine = true
        textSize = 14f
        minHeight = context.dp(48)
        setTextColor(idleText)
        setHintTextColor(mutedText)
        typeface = context.v3Font(500)
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
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

    /** 标题行末端的「清空」：浅色胶囊，48dp 热区；没有可清的条件时置灰且不可点。 */
    fun styleResetAction(button: TextView) {
        button.apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            textSize = 14f
            typeface = context.v3Font(600)
            setTextColor(palette.textAccent)
            val visible = RippleDrawable(
                ColorStateList.valueOf(palette.alpha20),
                palette.pillSecondary(999f, context.hairlinePx()),
                shape(999f, Color.WHITE),
            )
            background = InsetDrawable(visible, 0, context.dp(6), 0, context.dp(6))
            setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
            pressScale()
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

    private fun selectedStates(selected: Drawable, idle: Drawable? = null): StateListDrawable =
        StateListDrawable().apply {
            setEnterFadeDuration(FADE_MS)
            setExitFadeDuration(FADE_MS)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), idle ?: shape(0f, Color.TRANSPARENT))
        }

    private fun stateColors(selected: Int, idle: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(selected, idle),
    )

    /** 选中态：背景 / 字色走 state_selected 渐变；字重没法渐变，直接换（初始是未选的 500）。 */
    private fun TextView.setChosen(chosen: Boolean) {
        if (isSelected == chosen) return
        isSelected = chosen
        typeface = context.v3Font(if (chosen) 600 else 500)
    }

    private fun matchWidth(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).also { it.topMargin = context.dp(topMargin) }

    private companion object {
        /** 等分整行的上限：再多每段就挤不下两个汉字 + 大字体了。 */
        const val EQUAL_SEGMENT_LIMIT = 4
        const val FADE_MS = 180
    }
}
