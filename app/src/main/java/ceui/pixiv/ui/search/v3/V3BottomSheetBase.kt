package ceui.pixiv.ui.search.v3

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.utils.V3Palette
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * 4 个 V3 sheet（[SearchFilterV3BottomSheet] / [SimplePickerSheet] / [DurationPickerSheet]
 * / [OtherFilterSheet]）共享的 BottomSheet 基类。把重复的 onCreateDialog + onStart
 * （behavior 配置 + 透明背景）+ onViewCreated（systemBars insets）抽出来，子类只关心 layout 和业务。
 *
 * 子类在 [onViewCreated] 调 `super.onViewCreated(...)` 后再做自己的 binding 绑定。
 */
abstract class V3BottomSheetBase : BottomSheetDialogFragment() {

    /** 子类 lazy 来需要 palette 时拿 —— 受 `requireContext()` 限制，仅在 fragment attached 之后调。 */
    protected val palette by lazy { V3Palette.from(requireContext()) }

    /**
     * 默认 sheet 占屏高比例。子类可 override。
     * SimplePickerSheet 列表偏长 → 0.85；其余都行，保持 0.85 默认即可，主 sheet 给 0.92。
     */
    protected open val maxHeightFraction: Float = 0.85F

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        // 用 V3 专用 theme 把 edgeToEdge 打开 —— Material 会顺手 setDecorFitsSystemWindows=false
        // + nav bar 透明，dialog window 就能画到 gesture nav 底下。
        BottomSheetDialog(requireContext(), R.style.ThemeOverlay_V3_BottomSheetDialog)

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.maxHeight = (screenHeight * maxHeightFraction).roundToInt()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // 我们自己的 root 用 bg_v3_sheet_top（顶部圆角）画背景；把 Material 默认的擦掉，
        // 否则会盖掉圆角。
        sheet.background = ColorDrawable(Color.TRANSPARENT)
        // Material 在 edgeToEdge=true 时给 design_bottom_sheet 装了 inset listener，
        // 会把 nav inset pad 到 sheet 容器本身 → 我们的 root MATCH_PARENT 被压缩，root 的
        // V3 bg 就盖不到 nav bar 区域，scrim 透出来形成那条灰带。改成 noop：root 的 bg
        // 撑满到屏幕底，nav bar padding 由 [onViewCreated] 里对 root 的 listener 单独做。
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets -> insets }
        sheet.setPadding(0, 0, 0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 分段样式要等子类在自己的 onViewCreated 里把行显隐 / 动态行都定完再套，post 一拍。
        view.post { applySegmentedCardStyle() }
        // 底 inset 取「系统栏 / 输入法」两者较高的那个（getInsets 传 or 起来的 type 就是逐边取 max）。
        // 必须吃 Type.ime()：本基类走 edgeToEdge（见 [onCreateDialog]），Material 会
        // setDecorFitsSystemWindows(window, false)，dialog window 从此不再随软键盘 resize/pan
        // —— 不自己垫 ime 的话键盘直接盖在 sheet 上，带输入框的 sheet（范围输入 / HEX 取色）
        // 整个输入区看不见（issue #1024）。垫上之后内容被抬到键盘之上，sheet 背景照旧铺到屏幕底。
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            v.updatePadding(bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * 把 `android:tag="v3_card"` 的 settings 卡片重排成新版设置页同款 MD3-E 分段分组：
     * 卡容器去掉整卡底，卡内每一行单独套 [V3Palette.cardFill] 底 + 分段圆角
     * （段首 20dp / 段中 5dp / 段尾 20dp，同 bg_m3_row_*），行间 1dp 分割线转成 2dp 透明间隙。
     *
     * 子类里行的显隐（illust/novel 模式）会影响首尾判定，所以在 base 的 [onViewCreated]
     * 里 post 执行；之后再动态改行显隐的 sheet（如 DateRangePickerSheet 的「清除日期」行）
     * 改完自己再调一次这个方法。
     *
     * 分类规则（只看 visibility != GONE 的直接子 view）：
     * - 纯 View（1dp 分割线）→ 2dp 透明间隙；
     * - ViewGroup 或可点击的 TextView（文字动作行）→ 分段行；
     * - 不可点击的 TextView（卡内小节标题）→ 断段，自身不套底。
     */
    protected fun applySegmentedCardStyle() {
        val root = view ?: return
        forEachTagged(root, V3_CARD_TAG) { card ->
            if (card is ViewGroup) restyleCardSegments(card)
        }
    }

    private fun restyleCardSegments(card: ViewGroup) {
        val d = card.resources.displayMetrics.density
        val gapPx = (2 * d).roundToInt()
        val strokePx = (0.5f * d).roundToInt().coerceAtLeast(1)
        val radiusBig = 20f * d
        val radiusSmall = 5f * d

        card.background = null
        if (card is android.widget.LinearLayout) card.clipToOutline = false

        val segments = mutableListOf<List<View>>()
        var current = mutableListOf<View>()
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            if (child.visibility == View.GONE) continue
            when {
                child !is ViewGroup && child !is TextView -> {
                    // 1dp 分割线 → 2dp 透明间隙（保留 view 本体，业务代码还会切它的显隐）
                    child.background = null
                    (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.height = gapPx
                        lp.marginStart = 0
                        lp.marginEnd = 0
                        child.layoutParams = lp
                    }
                }
                child is ViewGroup || child.isClickable -> current.add(child)
                else -> {
                    // 卡内小节标题：断段
                    if (current.isNotEmpty()) {
                        segments.add(current)
                        current = mutableListOf()
                    }
                }
            }
        }
        if (current.isNotEmpty()) segments.add(current)

        val highlight = TypedValue().let { tv ->
            card.context.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)
            tv.data
        }
        for (segment in segments) {
            segment.forEachIndexed { idx, row ->
                val top = if (idx == 0) radiusBig else radiusSmall
                val bottom = if (idx == segment.size - 1) radiusBig else radiusSmall
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
                    setColor(palette.cardFill)
                    setStroke(strokePx, palette.cardHairline)
                }
                // 行原本的 ?attr/selectableItemBackground 被换掉，用 ripple 包住保留按压反馈
                row.background = RippleDrawable(ColorStateList.valueOf(highlight), shape, null)
            }
        }
    }

    private fun forEachTagged(v: View, tag: String, action: (View) -> Unit) {
        if (v.tag == tag) action(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) forEachTagged(v.getChildAt(i), tag, action)
        }
    }
}

/** 卡片 tag —— 与各 dialog_*.xml 里 settings 卡片的 `android:tag` 对应。 */
private const val V3_CARD_TAG = "v3_card"
