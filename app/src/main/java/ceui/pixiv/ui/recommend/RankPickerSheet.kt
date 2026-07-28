package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemRankPickerRowBinding
import ceui.lisa.databinding.SheetRankPickerBinding
import ceui.lisa.utils.V3Palette
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 榜单选项选择 bottom sheet:标签专区选标签、年代榜选年份共用。选项来自宿主已经拉好的
 * 列表(通过 arguments 传数组,不重打网络 —— 也因此旋转/进程恢复重建后数据仍在);
 * 单击即选:回调宿主换 feed,然后自关。
 *
 * chrome 约定(theme overlay / edgeToEdge / maxHeight / 透明 sheet 容器)照抄
 * [ceui.pixiv.ui.bookmark.SelectTagBottomSheet],那边有完整论证。与它不同的是本 sheet
 * 挂在**宿主 fragment 的 childFragmentManager** 上:回调走 parentFragment 强转,重建后
 * FragmentManager 会把 sheet 挂回同一个宿主,回调链路天然恢复,不需要 listener 重绑。
 */
class RankPickerSheet : BottomSheetDialogFragment() {

    /** 宿主契约。[onRankOptionPicked] 收的是选项的 **key**(服务端 enum 语义:tag 原文 / 年份)。 */
    interface Host {
        fun onRankOptionPicked(key: String)
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetRankPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetRankPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val keys = args.getStringArray(ARG_KEYS) ?: emptyArray()
        val labels = args.getStringArray(ARG_LABELS) ?: emptyArray()
        val sublabels = args.getStringArray(ARG_SUBLABELS) ?: emptyArray()
        val counts = args.getIntArray(ARG_COUNTS) ?: IntArray(0)
        val selected = args.getInt(ARG_SELECTED, 0)
        if (keys.isEmpty()) {
            // 理论上到不了(宿主拿到空列表就不给开 sheet),防御性自关别留一张空浮层。
            dismissAllowingStateLoss()
            return
        }
        binding.sheetTitle.text = args.getString(ARG_TITLE).orEmpty()
        binding.optionList.layoutManager = LinearLayoutManager(requireContext())
        binding.optionList.adapter = RowAdapter(
            keys, labels, sublabels, counts, selected, V3Palette.from(requireContext()),
        ) { key ->
            (parentFragment as? Host)?.onRankOptionPicked(key)
            dismissAllowingStateLoss()
        }
        // 打开时把选中项滚到可视区中部,几十行的列表不用用户自己找。selected 可为 -1
        // (当前项掉出列表,无高亮行),此时停在列表顶部。
        if (selected >= 0) {
            (binding.optionList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(selected, screenHeight / 4)
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class RowAdapter(
        private val keys: Array<String>,
        private val labels: Array<String>,
        private val sublabels: Array<String>,
        private val counts: IntArray,
        private val selected: Int,
        private val palette: V3Palette,
        private val onPick: (String) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.RowHolder>() {

        class RowHolder(val binding: ItemRankPickerRowBinding) : RecyclerView.ViewHolder(binding.root) {
            // 布局默认字色(主题 resolve 后的值)。选中行染强调色后 holder 会被复用,
            // 未选中行必须用这份快照染回去,不能指望 XML —— XML 只在 inflate 时生效一次。
            val defaultPrimaryColor = binding.optionPrimary.textColors
            val defaultCountColor = binding.optionCount.textColors
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(ItemRankPickerRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = keys.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val b = holder.binding
            b.optionPrimary.text = labels.getOrElse(position) { keys[position] }
            b.optionCount.text = formatRankCount(counts.getOrElse(position) { 0 })
            val sub = sublabels.getOrNull(position)?.takeIf { it.isNotEmpty() }
            if (sub != null) {
                b.optionSecondary.text = sub
                b.optionSecondary.visibility = View.VISIBLE
            } else {
                b.optionSecondary.visibility = View.GONE
            }
            // 选中行:主文字染强调色;其余行染回默认色(holder 复用,不能只染不还)。
            // 主题非 MD3,强调色统一代码 tint(见 sheet 布局注释)。
            if (position == selected) {
                b.optionPrimary.setTextColor(palette.textAccent)
                b.optionCount.setTextColor(palette.textAccent)
            } else {
                b.optionPrimary.setTextColor(holder.defaultPrimaryColor)
                b.optionCount.setTextColor(holder.defaultCountColor)
            }
            b.root.setOnClickListener { onPick(keys[position]) }
        }
    }

    companion object {
        const val TAG = "RankPickerSheet"
        private const val MAX_HEIGHT_FRACTION = 0.88F
        private const val ARG_TITLE = "title"
        private const val ARG_KEYS = "keys"
        private const val ARG_LABELS = "labels"
        private const val ARG_SUBLABELS = "sublabels"
        private const val ARG_COUNTS = "counts"
        private const val ARG_SELECTED = "selected"

        /**
         * [fm] 传宿主的 childFragmentManager(回调靠 parentFragment 解析,见类注释)。
         * TAG 去重挡连点(同 SelectTagBottomSheet 那道闸)。[labels]/[sublabels]/[counts]
         * 与 [keys] 等长;副行不需要的位置放空串(Bundle 不便携带 null 数组元素的语义)。
         */
        @JvmStatic
        fun show(
            fm: FragmentManager,
            title: String,
            keys: Array<String>,
            labels: Array<String>,
            sublabels: Array<String>,
            counts: IntArray,
            selected: Int,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            RankPickerSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArray(ARG_KEYS, keys)
                    putStringArray(ARG_LABELS, labels)
                    putStringArray(ARG_SUBLABELS, sublabels)
                    putIntArray(ARG_COUNTS, counts)
                    putInt(ARG_SELECTED, selected)
                }
            }.show(fm, TAG)
        }
    }
}

/**
 * 榜单场景的计数缩写:12345 → 「12.3k」,988 → 「988」。sheet 行 / 宿主选择条共用。
 * Locale.US 钉死小数点 —— ru/tr 等 locale 的 %.1f 是逗号小数(「20,0k」),这里不合适。
 */
internal fun formatRankCount(n: Int): String =
    if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000f) else n.toString()
