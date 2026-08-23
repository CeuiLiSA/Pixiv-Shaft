package ceui.pixiv.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.bundleOf
import java.util.LinkedHashSet
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.SheetAiBlockExemptAuthorsBinding
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
import kotlin.math.roundToInt

/**
 * 「豁免的作者列表」管理 sheet：逐个添加、垃圾桶按钮删除，不再让用户手填逗号/换行。
 *
 * 改动只在本 sheet 内生效；点「保存」时才写回 [Shaft.sSettings.aiBlockExemptAuthorIds]，
 * 内部使用 [LinkedHashSet] 保存，保持添加顺序并自动去重。
 * 见 Shaft.sSettings.getAiBlockExemptAuthorIds()。
 */
class AiBlockExemptAuthorsSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetAiBlockExemptAuthorsBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    /**
     * sheet 内的工作副本。只在 [onCreate] 从设置里灌一次：DialogFragment 会跨旋转/配置变更存活，
     * 若放在 onViewCreated 里 addAll，转屏一次就把用户刚删掉、还没保存的 ID 又加回来。
     */
    private val ids = LinkedHashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ids.addAll(Shaft.sSettings.aiBlockExemptAuthorIds)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAiBlockExemptAuthorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyAccent()
        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnAdd.setOnClickListener { addCurrentInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }
        renderIds()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        makeSheetTransparentAndFillNavBar()
    }

    private fun addCurrentInput() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        // 作者 ID 必须是正整数：IllustNovelFilter.isAiExemptAuthor 对 <=0 一律不认，
        // 这里不拦的话「0」会被加进列表、保存成功却永远不生效。
        val id = text.toLongOrNull()
        if (id == null || id <= 0L) {
            Common.showToast(getString(R.string.ai_block_exempt_invalid))
            return
        }
        if (!ids.add(id)) {
            Common.showToast(getString(R.string.ai_block_exempt_duplicate))
            return
        }
        binding.input.text?.clear()
        renderIds()
    }

    private fun renderIds() {
        val flow = binding.idFlow
        flow.removeAllViews()
        binding.emptyHint.isVisible = ids.isEmpty()
        ids.forEach { id -> flow.addView(createIdChip(id)) }
    }

    private fun createIdChip(id: Long): View {
        val density = resources.displayMetrics.density
        val chip = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999F * density
                setColor(palette.alpha20)
                setStroke((1.5F * density).roundToInt().coerceAtLeast(1), palette.alpha50)
            }
            setPadding(
                (15 * density).roundToInt(),
                (6 * density).roundToInt(),
                (8 * density).roundToInt(),
                (6 * density).roundToInt(),
            )
        }
        val label = TextView(requireContext()).apply {
            text = id.toString()
            textSize = 13.5F
            setTextColor(palette.textAccent)
            includeFontPadding = false
        }
        val delete = AppCompatImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_delete_black_24dp)
            imageTintList = ColorStateList.valueOf(palette.textAccent)
            contentDescription = getString(R.string.action_delete)
            setOnClickListener {
                ids.remove(id)
                renderIds()
            }
        }
        chip.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        chip.addView(
            delete,
            LinearLayout.LayoutParams(18.ppppx, 18.ppppx).apply { marginStart = 8.ppppx },
        )
        chip.layoutParams = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = 8.ppppx
            bottomMargin = 8.ppppx
            flexShrink = 0F
        }
        return chip
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
    }

    private fun save() {
        Shaft.sSettings.aiBlockExemptAuthorIds = LinkedHashSet(ids)
        Local.setSettings(Shaft.sSettings)
        Common.showToast(getString(R.string.please_restart_app), 2)
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHANGED to true))
        dismissAllowingStateLoss()
    }

    companion object {
        const val REQUEST_KEY = "ai_block_exempt_authors_changed"
        const val RESULT_CHANGED = "changed"
        private const val MAX_HEIGHT_FRACTION = 0.75F
    }
}
