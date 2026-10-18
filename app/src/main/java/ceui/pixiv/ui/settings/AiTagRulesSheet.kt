package ceui.pixiv.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.SheetAiTagRulesBinding
import ceui.lisa.helper.AiTagRules
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import ceui.pixiv.witstudio.theme.V3Palette
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.LinkedHashSet
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「将带这些标签的视为AI生成」管理 sheet（issue #1252 需求 1）。
 *
 * 分两段：**内置标签**编译期固定在 [AiTagRules.builtinTags]，只能停用 / 启用，不挂垃圾桶；
 * **我添加的标签**可停用、可删除。两段的交互语言与兄弟 sheet
 * [AiBlockExemptAuthorsSheet] 一致（点胶囊切换启用态，虚线描边 = 停用），因为它们挂在
 * 同一个设置分组下，用户不该在这里换一套操作习惯。
 *
 * ## 为什么不用 RecyclerView
 * 内置 24 条 + 自定义通常个位数，全量也就三十来个胶囊。这里用两个 [FlexboxLayout] 直接挂
 * 子 View：增删只动那一个胶囊、切换只重画被点的那一个，没有整表重建，也不需要
 * ItemAnimator 那套「插入 + 滚动」的拆帧处理（那正是兄弟 sheet 里最绕的部分）。
 *
 * ## 落盘
 * 工作副本在 [customTags] 与 [disabledKeys]，只在 [onCreate] 从设置灌一次（DialogFragment
 * 跨旋转存活，放 onViewCreated 里重灌会把用户没保存的改动冲掉）。点「保存」才写回：
 * `aiTagUserTags`（全部自定义标签）与 `aiTagDisabledTags`（停用项，内置与自定义混装）。
 * 旧设置 / WebDAV 老备份没有这两个字段，反序列化为空集 = 内置全启用、无自定义，无需迁移。
 *
 * [disabledKeys] 里存的是 [AiTagRules.normalize] 之后的键，写回时再还原成标签本来的写法——
 * 否则用户加「NovelAI」、停用它、再保存，列表里会多出一个大小写不同的孪生条目。
 */
class AiTagRulesSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetAiTagRulesBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    /** 工作副本：自定义标签（保持添加顺序）。只在 [onCreate] 灌一次，见类注释。 */
    private val customTags = mutableListOf<String>()

    /** 工作副本：被停用的标签（规范化后的键，内置与自定义混装）。 */
    private val disabledKeys = mutableSetOf<String>()

    private val builtinChips = mutableListOf<ChipViews>()
    private val customChips = mutableListOf<ChipViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customTags.clear()
        customTags.addAll(AiTagRules.sanitized(Shaft.sSettings.aiTagUserTags))
        disabledKeys.clear()
        Shaft.sSettings.aiTagDisabledTags.forEach { disabledKeys.add(AiTagRules.normalize(it)) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAiTagRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        builtinChips.clear()
        customChips.clear()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyAccent()
        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnAdd.setOnClickListener { addCurrentInput() }
        binding.btnEnableAll.setOnClickListener { setAllEnabled(true) }
        binding.btnDisableAll.setOnClickListener { setAllEnabled(false) }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }
        AiTagRules.builtinTags().forEach { tag ->
            builtinChips.add(buildChip(binding.builtinChips, tag, deletable = false))
        }
        customTags.forEach { tag ->
            customChips.add(buildChip(binding.customChips, tag, deletable = true))
        }
        renderEmpty()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            // 拖拽保持启用（把手 / 标题 / 底部动作条都能拖）。列表滚动的余量不驱动 sheet——
            // 由布局里那层 WitNestedScrollBlockingLayout 在中间截停，与兄弟 sheet 同款。
            maxHeight = collapsedMaxHeight()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        makeSheetTransparentAndFillNavBar()
        // 输入框弹键盘时同步放宽 sheet 高度上限：默认 0.75 屏高，键盘占掉一半后留给胶囊区的
        // 高度会不够看（同 AiBlockExemptAuthorsSheet 的处理）。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            dialog.behavior.maxHeight = min(screenHeight - bars.top, collapsedMaxHeight() + ime)
            v.updatePadding(bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** 无键盘时的 sheet 高度上限（屏高的 [MAX_HEIGHT_FRACTION]）。 */
    private fun collapsedMaxHeight(): Int = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()

    private fun addCurrentInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Common.showToast(getString(R.string.ai_tag_rules_invalid))
            return
        }
        // 内置标签不能重复添加（它已经在上一段里了）；自定义之间也按规范化后去重，
        // 免得「NovelAI」和「novelai」两条各自生效、用户却以为自己只加了一条。
        if (AiTagRules.isBuiltinTag(text) || AiTagRules.containsTag(customTags, text)) {
            Common.showToast(getString(R.string.ai_tag_rules_duplicate))
            return
        }
        binding.input.text?.clear()
        customTags.add(text)
        customChips.add(buildChip(binding.customChips, text, deletable = true))
        renderEmpty()
        // 新胶囊追加在末尾，滚到底让它进入视野（键盘可能正挡着）。
        // post 的这一刻视图可能已经被销毁（保存后立刻 dismiss），所以走 _binding 空安全访问。
        binding.scroll.post { _binding?.scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 一键把两段胶囊全切到同一状态。
     *
     * 只动 [disabledKeys] 再逐条重画，不走「重建视图」：内置 24 条 + 自定义若干个，
     * 重画只是改渐变底色与文字色，比拆了重建快得多，也不会打断正在进行的滚动。
     */
    private fun setAllEnabled(enabled: Boolean) {
        if (enabled) {
            disabledKeys.clear()
        } else {
            (builtinChips + customChips).forEach { disabledKeys.add(AiTagRules.normalize(it.tag)) }
        }
        (builtinChips + customChips).forEach { renderChip(it, enabled) }
    }

    private fun toggle(views: ChipViews) {
        val key = AiTagRules.normalize(views.tag)
        val enabled = if (!disabledKeys.remove(key)) {
            disabledKeys.add(key)
            false
        } else {
            true
        }
        renderChip(views, enabled)
    }

    private fun removeCustom(views: ChipViews) {
        if (!customChips.remove(views)) return
        customTags.remove(views.tag)
        disabledKeys.remove(AiTagRules.normalize(views.tag))
        binding.customChips.removeView(views.chip)
        renderEmpty()
    }

    private fun renderEmpty() {
        binding.customEmpty.isVisible = customTags.isEmpty()
    }

    private fun applyAccent() {
        binding.inputLayout.boxStrokeColor = palette.textAccent
        binding.inputLayout.hintTextColor = ColorStateList.valueOf(palette.textAccent)
        binding.input.setTextColor(requireContext().getColor(R.color.v3_text_1))
        binding.btnAdd.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnAdd.setTextColor(Color.WHITE)
        binding.btnSave.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnSave.setTextColor(Color.WHITE)
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnEnableAll.setTextColor(palette.textAccent)
        binding.btnDisableAll.setTextColor(palette.textAccent)
    }

    private fun save() {
        val disabled = LinkedHashSet<String>()
        // 写回标签本来的写法（内置用清单里的原始拼写，自定义用用户输入的），而不是规范化后的键。
        AiTagRules.builtinTags().forEach { tag ->
            if (disabledKeys.contains(AiTagRules.normalize(tag))) disabled.add(tag)
        }
        customTags.forEach { tag ->
            if (disabledKeys.contains(AiTagRules.normalize(tag))) disabled.add(tag)
        }
        Shaft.sSettings.aiTagUserTags = LinkedHashSet(customTags)
        Shaft.sSettings.aiTagDisabledTags = disabled
        Local.setSettings(Shaft.sSettings)
        Common.showToast(getString(R.string.please_restart_app), 2)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHANGED to true))
        dismissAllowingStateLoss()
    }

    /** 一个胶囊的视图句柄。[delete] 只有自定义标签才有（内置不能删）。 */
    private class ChipViews(
        val tag: String,
        val chip: LinearLayout,
        val label: TextView,
        val delete: AppCompatImageView?,
    )

    private fun buildChip(container: FlexboxLayout, tag: String, deletable: Boolean): ChipViews {
        val ctx = container.context
        val chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = 8.ppppx
                bottomMargin = 8.ppppx
                flexShrink = 0F
            }
        }
        val label = TextView(ctx).apply {
            textSize = 13.5F
            includeFontPadding = false
            compoundDrawablePadding = 6.ppppx
            text = tag
        }
        chip.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val delete = if (deletable) {
            AppCompatImageView(ctx).apply {
                setImageResource(R.drawable.ic_delete_black_24dp)
                contentDescription = ctx.getString(R.string.action_delete)
            }.also {
                chip.addView(
                    it,
                    LinearLayout.LayoutParams(18.ppppx, 18.ppppx).apply { marginStart = 8.ppppx },
                )
            }
        } else {
            null
        }
        val views = ChipViews(tag, chip, label, delete)
        renderChip(views, !disabledKeys.contains(AiTagRules.normalize(tag)))
        // 整条胶囊是切换热区；删除键自己消费点击，不会误触切换。
        chip.setOnClickListener { toggle(views) }
        delete?.setOnClickListener { removeCustom(views) }
        container.addView(chip)
        return views
    }

    /**
     * 把一条胶囊按当前状态画出来（复用同一实例，必须把所有状态重设一遍）：
     * - 启用：主题浅底 + 实线描边 + 勾，字色 [V3Palette.textAccent]；
     * - 停用：「挂着但没工作」——淡底 + 虚线描边、不挂图标，字色降一档。
     *
     * 与 [AiBlockExemptAuthorsSheet] 的胶囊同一套视觉语言，改这里时那边要一起改。
     */
    private fun renderChip(views: ChipViews, enabled: Boolean) {
        val chip = views.chip
        val label = views.label
        val delete = views.delete
        val ctx = chip.context
        val d = chip.resources.displayMetrics.density
        val bg = chip.background as? GradientDrawable ?: return
        bg.cornerRadius = 999F * d
        val stroke = (1.5F * d).roundToInt().coerceAtLeast(1)
        if (enabled) {
            bg.setColor(palette.alpha20)
            bg.setStroke(stroke, palette.alpha50)
        } else {
            bg.setColor(palette.alpha08)
            bg.setStroke(stroke, palette.alpha15, 5F * d, 4F * d)
        }
        chip.setPadding(
            (15 * d).roundToInt(),
            (6 * d).roundToInt(),
            if (delete != null) (8 * d).roundToInt() else (15 * d).roundToInt(),
            (6 * d).roundToInt(),
        )
        val tint = if (enabled) palette.textAccent else ctx.getColor(R.color.v3_text_2)
        label.setTextColor(tint)
        // 只有「启用」挂勾。停用不挂图标：eye-off 在「判定规则」语境里会被读成「隐藏」，
        // 靠淡底 + 虚线描边 + 降档字色表达「挂着但没工作」就够了。
        val icon = if (enabled) {
            AppCompatResources.getDrawable(ctx, R.drawable.ic_check_24dp)?.mutate()?.apply {
                setTint(tint)
                setBounds(0, 0, 14.ppppx, 14.ppppx)
            }
        } else {
            // 透明占位：停用态不挂勾，但保留同尺寸空白，切换时胶囊宽度不变、换行布局不回流。
            ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 14.ppppx, 14.ppppx) }
        }
        label.setCompoundDrawablesRelative(icon, null, null, null)
        delete?.imageTintList = ColorStateList.valueOf(tint)
        chip.contentDescription = views.tag + " " + ctx.getString(
            if (enabled) R.string.ai_block_exempt_state_on else R.string.ai_block_exempt_state_off,
        )
    }

    companion object {
        const val REQUEST_KEY = "ai_tag_rules_changed"
        const val RESULT_CHANGED = "changed"
        private const val MAX_HEIGHT_FRACTION = 0.75F
    }
}