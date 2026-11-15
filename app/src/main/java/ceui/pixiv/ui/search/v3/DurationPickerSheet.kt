package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.DialogSearchFilterDurationBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import java.io.Serializable

/**
 * 「投稿期间」picker —— iOS pixiv 8.6.6 一致：7 行 flat list 单选。
 *
 *   0 = 不限                              （清掉所有日期）
 *   1 = 24 小时内 / 一周内 / 一月内 / 半年内 / 一年内   （DurationBucket 5 档）
 *   2..5
 *   6 = 指定期间（点开后弹 [DateRangePickerSheet]）
 *
 * picker 自身只回 bucket 或 「请求弹自定义日期」；自定义日期是 [DateRangePickerSheet] 单独
 * 一个 sheet 处理（带起 / 止两个 DatePicker + 自动续弹 + 确认按钮）。父 sheet
 * [SearchFilterV3BottomSheet] 同时监听两条 FragmentResult，分别回填到 [SearchFilterV3]。
 */
class DurationPickerSheet : V3BottomSheetBase() {

    private var _binding: DialogSearchFilterDurationBinding? = null
    private val binding get() = _binding!!

    /**
     * picker 回传给父 sheet 的结果。
     *  - [bucket] = 选了某个相对档（5 个之一）；customStart/customEnd 一律为 null
     *  - 全部为 null = 选了「不限」
     *  - [openCustomRange] = 选了「指定期间」，父 sheet 应弹 [DateRangePickerSheet]；
     *    bucket = null，custom dates 在另一条 result 里回传
     */
    data class Patch(
        val bucket: DurationBucket?,
        val openCustomRange: Boolean,
    ) : Serializable

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterDurationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }

        @Suppress("DEPRECATION")
        val initialBucket = requireArguments().getSerializable(ARG_INITIAL_BUCKET) as? DurationBucket
        val hasCustomDates = requireArguments().getBoolean(ARG_INITIAL_HAS_CUSTOM, false)
        renderRows(initialBucket, hasCustomDates)
    }

    /**
     * @param selectedBucket 当前选中的预设档；null 表示「不限」或「指定期间」
     * @param hasCustomDates 当前 SearchFilterV3 已经设置了 startDate/endDate 之一
     *   （此时选中态指向「指定期间」即 idx 6）
     */
    private fun renderRows(selectedBucket: DurationBucket?, hasCustomDates: Boolean) {
        val labels = listOf(
            getString(R.string.search_filter_v3_duration_all),
            getString(R.string.search_filter_v3_duration_24h),
            getString(R.string.search_filter_v3_duration_week),
            getString(R.string.search_filter_v3_duration_month),
            getString(R.string.search_filter_v3_duration_half_year),
            getString(R.string.search_filter_v3_duration_year),
            getString(R.string.search_filter_v3_duration_custom),
        )
        val selectedIdx = when {
            hasCustomDates && selectedBucket == null -> 6
            selectedBucket == DurationBucket.Last24Hours -> 1
            selectedBucket == DurationBucket.LastWeek -> 2
            selectedBucket == DurationBucket.LastMonth -> 3
            selectedBucket == DurationBucket.LastHalfYear -> 4
            selectedBucket == DurationBucket.LastYear -> 5
            else -> 0
        }

        binding.pickerCard.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        labels.forEachIndexed { idx, label ->
            if (idx > 0) binding.pickerCard.addView(divider())
            val rowBinding = CellSearchFilterCheckRowBinding.inflate(
                inflater, binding.pickerCard, false,
            )
            rowBinding.checkLabel.text = label
            rowBinding.checkMark.isInvisible = idx != selectedIdx
            rowBinding.checkMark.setTextColor(palette.textAccent)
            rowBinding.root.setOnClick { handlePick(idx) }
            binding.pickerCard.addView(rowBinding.root)
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(resources.getColor(R.color.v3_border_2, null))
        layoutParams = android.widget.LinearLayout.LayoutParams(MATCH_PARENT, 1.ppppx).apply {
            marginStart = 18.ppppx
        }
    }

    private fun handlePick(idx: Int) {
        val (bucket, openCustom) = when (idx) {
            0 -> null to false   // 不限
            1 -> DurationBucket.Last24Hours to false
            2 -> DurationBucket.LastWeek to false
            3 -> DurationBucket.LastMonth to false
            4 -> DurationBucket.LastHalfYear to false
            5 -> DurationBucket.LastYear to false
            6 -> null to true    // 指定期间 —— 让父 sheet 弹 DateRangePickerSheet
            else -> null to false
        }
        parentFragmentManager.setFragmentResult(requestKey, bundleOf(KEY_PATCH to Patch(bucket, openCustom)))
        dismissAllowingStateLoss()
    }

    companion object {
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL_BUCKET = "initialBucket"
        private const val ARG_INITIAL_HAS_CUSTOM = "initialHasCustom"

        fun newInstance(
            requestKey: String,
            current: SearchFilterV3,
        ): DurationPickerSheet = DurationPickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putSerializable(ARG_INITIAL_BUCKET, current.durationBucket)
                putBoolean(ARG_INITIAL_HAS_CUSTOM,
                    current.startDate != null || current.endDate != null)
            }
        }
    }
}
