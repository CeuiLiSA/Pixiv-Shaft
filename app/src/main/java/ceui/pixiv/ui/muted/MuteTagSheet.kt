package ceui.pixiv.ui.muted

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.SheetMuteTagBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.V3Palette
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * 「屏蔽设定」MD3-Expressive bottom sheet —— 取代 legacy `ceui.lisa.dialogs.MuteDialog`
 *（居中弹窗 + zhy TagFlowLayout + 三颗 Borderless 按钮）。
 *
 * 视觉与 [ceui.pixiv.ui.bookmark.SelectTagBottomSheet] 同源：拖拽把手 + headline/supporting
 * 双行标题 + tonal 次操作 + 底部 filled 主操作。强调色全部由 [V3Palette] 在运行时 tint，
 * 跟随用户在设置里选的主题色（root 挂的 Material3 主题会把 colorPrimary 顶成 MD3 基线紫）。
 *
 * ## 标签胶囊的三态
 * | 状态 | 表现 |
 * |---|---|
 * | 未屏蔽 | 描边胶囊，无图标 |
 * | 已屏蔽 | 强调色实底 + 强调色描边 + 勾图标 |
 * | 已屏蔽但未生效 | 同上，但换 eye-off 图标 + 虚线描边 + 文字降一档，并缀「未生效」 |
 *
 * 三态之间只换色和图标，圆角始终是胶囊。状态切换即时生效，没有过渡动画——
 * 勾选是个开关，不是一段表演。
 *
 * ## 语义：真正的开关，不是只进不出
 * legacy 版把「已屏蔽」的标签预选中，但取消勾选再确定**什么也不会发生**——只有新增会写库。
 * 一个长得像开关、却只单向生效的控件是在骗人。本版保存时按差集写：
 * 新勾上的 → [PixivOperate.muteTag]，被取消勾选的 → [PixivOperate.unMuteTag]；
 * 保持原样的一个都不动（重新 mute 会把「未生效」的记录重置成生效，那是数据损失）。
 */
class MuteTagSheet : BottomSheetDialogFragment() {

    // edgeToEdge：让 window 画到导航栏底下，root 的圆角背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetMuteTagBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    @Suppress("UNCHECKED_CAST")
    private val tags: List<TagsBean> by lazy {
        (arguments?.getSerializable(Params.CONTENT) as? ArrayList<TagsBean>).orEmpty()
    }

    /** 打开这一刻已在屏蔽表里的标签名（含「未生效」的）。保存时与 [selected] 做差集。 */
    private val originallyMuted = mutableSetOf<String>()

    /** 已屏蔽但开关关掉的标签名 —— 只影响显示，不影响差集判定。 */
    private val ineffective = mutableSetOf<String>()

    /** 当前勾选集合，初值 = [originallyMuted]。 */
    private val selected = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetMuteTagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        readMutedState()
        applyAccent()

        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, MUTED_TAGS_ROUTE)
            })
            dismissAllowingStateLoss()
        }

        binding.emptyHint.isVisible = tags.isEmpty()
        binding.btnSave.isVisible = tags.isNotEmpty()
        buildChips()
        updateSummary()
    }

    /**
     * 读一次屏蔽表，按标签名匹配（与 legacy 一致：正则模式的记录也只按 name 比对）。
     * DB 是 `allowMainThreadQueries` 的同步库，条数量级在几百，这里直接读。
     */
    private fun readMutedState() {
        val muted = IllustNovelFilter.getMutedTags()
        val mutedByName = muted.filter { !it.name.isNullOrEmpty() }.associateBy { it.name }
        tags.forEach { tag ->
            val record = mutedByName[tag.name] ?: return@forEach
            originallyMuted.add(tag.name)
            if (!record.isEffective) ineffective.add(tag.name)
        }
        selected.addAll(originallyMuted)
    }

    /**
     * 强调色刷到两颗按钮上：保存 = filled 实心（主操作），屏蔽记录 = tonal 淡底（次操作）。
     * 用代码 tint 而非主题属性——见类注释。
     */
    private fun applyAccent() {
        binding.btnSave.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnSave.setTextColor(Color.WHITE)
        binding.btnHistory.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
        binding.btnHistory.setTextColor(palette.textAccent)
        binding.btnCancel.setTextColor(palette.textAccent)
    }

    private fun buildChips() {
        val flow = binding.tagFlow
        flow.removeAllViews()
        tags.forEach { flow.addView(createChip(it)) }
    }

    private fun createChip(tag: TagsBean): TextView {
        val name = tag.name
        val chip = TextView(requireContext()).apply {
            textSize = 13.5F
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            minHeight = 38.ppppx
            compoundDrawablePadding = 6.ppppx
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
            val lp = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = 8.ppppx
            lp.bottomMargin = 8.ppppx
            layoutParams = lp
        }
        chip.text = chipLabel(tag)
        renderChip(chip, name in selected, name in ineffective)
        chip.setOnClickListener {
            if (!selected.remove(name)) selected.add(name)
            chip.text = chipLabel(tag)
            renderChip(chip, name in selected, name in ineffective)
            updateSummary()
        }
        return chip
    }

    /**
     * 胶囊文案：`原文` +（有译名时）淡色小一号的译名 +（未生效时）「· 未生效」。
     * 译名和「未生效」都压成 [V3Palette.textSeries] 一档，主视线仍落在原文上。
     */
    private fun chipLabel(tag: TagsBean): CharSequence {
        val name = tag.name
        val builder = SpannableStringBuilder(name)
        val translated = tag.translated_name
        if (!translated.isNullOrEmpty() && translated != name) {
            appendDim(builder, "  $translated", 0.92F)
        }
        if (tag.name in ineffective && tag.name in selected) {
            appendDim(builder, "  ·  ${getString(R.string.mute_sheet_ineffective)}", 0.88F)
        }
        return builder
    }

    private fun appendDim(builder: SpannableStringBuilder, text: String, scale: Float) {
        val start = builder.length
        builder.append(text)
        builder.setSpan(
            ForegroundColorSpan(palette.textSeries),
            start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            RelativeSizeSpan(scale),
            start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * 把胶囊按当前状态画出来。所有颜色派生自主题强调色，日夜两套由 [V3Palette] 内部处理。
     *
     * 选中就是选中：状态切换即时生效，不做过渡动画、不做形状变形。
     * 未生效的记录用虚线描边区分：实线=正在生效，虚线=挂着但没工作。
     */
    private fun renderChip(chip: TextView, isSelected: Boolean, isIneffective: Boolean) {
        val d = resources.displayMetrics.density
        val bg = chip.background as? GradientDrawable ?: return
        bg.cornerRadius = 999F * d
        bg.setColor(if (isSelected) palette.alpha20 else palette.alpha08)
        val strokeWidth = ((if (isSelected) 1.5F else 1F) * d).roundToInt().coerceAtLeast(1)
        val strokeColor = if (isSelected) palette.alpha50 else palette.alpha15
        if (isSelected && isIneffective) {
            bg.setStroke(strokeWidth, strokeColor, 5F * d, 4F * d)
        } else {
            bg.setStroke(strokeWidth, strokeColor)
        }
        chip.setTextColor(
            when {
                !isSelected -> requireContext().getColor(R.color.v3_text_2)
                isIneffective -> V3Palette.withAlpha(palette.textAccent, 0.62F)
                else -> palette.textAccent
            }
        )

        val vPad = (8 * d).roundToInt()
        chip.setPadding(((if (isSelected) 13 else 15) * d).roundToInt(), vPad, (15 * d).roundToInt(), vPad)
        chip.setCompoundDrawablesRelative(leadingIcon(isSelected, isIneffective), null, null, null)
    }

    /** 勾（生效）/ eye-off（未生效）图标；未选中态不挂图标。 */
    private fun leadingIcon(isSelected: Boolean, isIneffective: Boolean) =
        if (!isSelected) {
            null
        } else {
            val res = if (isIneffective) {
                R.drawable.ic_visibility_off_black_24dp
            } else {
                R.drawable.ic_check_24dp
            }
            AppCompatResources.getDrawable(requireContext(), res)?.mutate()?.apply {
                setBounds(0, 0, 16.ppppx, 16.ppppx)
                setTint(
                    if (isIneffective) {
                        V3Palette.withAlpha(palette.textAccent, 0.62F)
                    } else {
                        palette.textAccent
                    }
                )
            }
        }

    private fun updateSummary() {
        binding.selectionSummary.text = if (tags.isEmpty()) {
            ""
        } else {
            getString(R.string.mute_sheet_summary, selected.size, tags.size)
        }
    }

    /**
     * 差集落库：新勾上的 mute，被取消勾选的 unmute，没动的一个都不碰
     *（重新 mute 会把「未生效」记录重置回生效）。
     */
    private fun save() {
        val toMute = tags.filter { it.name in selected && it.name !in originallyMuted }
        val toUnMute = originallyMuted - selected
        if (toMute.isEmpty() && toUnMute.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        toMute.forEach { PixivOperate.muteTag(it) }
        toUnMute.forEach { name ->
            // 删除按主键 (name.hashCode(), MUTE_TAG) 走，只需要名字；toast 交给外面统一发一条。
            PixivOperate.unMuteTag(TagsBean().apply { this.name = name }, false)
        }
        Common.showToast(getString(R.string.operate_success))
        dismissAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // sheet 容器透明 + 内容背景铺进底部 safe area：圆角与配色全由 root 的
        // bg_m3e_sheet_top 负责，否则主题默认 sheet 底会盖在圆角外、暗色模式露白。
        makeSheetTransparentAndFillNavBar()
    }

    companion object {
        const val TAG = "MuteTagSheet"

        /** 标签多时未必占满，给足高度即可；仍留一线底图暗示「这是一张浮层」。 */
        private const val MAX_HEIGHT_FRACTION = 0.82F

        /** [TemplateActivity] 的路由 key，不是 UI 文案。 */
        private const val MUTED_TAGS_ROUTE = "标签屏蔽记录"

        /**
         * 唯一入口。空名字的标签直接滤掉——[PixivOperate.muteTag] 上来就 `name.hashCode()`，
         * 喂 null 会当场 NPE。重复 show 由 TAG 挡掉（长按菜单容易连点）。
         */
        @JvmStatic
        fun show(fm: FragmentManager, tags: List<TagsBean>?) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            val payload = ArrayList(tags.orEmpty().filter { !it.name.isNullOrBlank() })
            MuteTagSheet().apply {
                arguments = Bundle().apply { putSerializable(Params.CONTENT, payload) }
            }.show(fm, TAG)
        }
    }
}
