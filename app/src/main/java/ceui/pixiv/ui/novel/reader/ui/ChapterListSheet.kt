package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderChapterRowBinding
import ceui.lisa.databinding.SheetReaderChaptersBinding
import ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

interface ChapterSheetCallback {
    fun onChapterSelected(entry: ChapterOutlineEntry)
}

class ChapterListSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReaderChaptersBinding? = null
    private val binding get() = _binding!!

    private val currentSourceStart: Int by lazy {
        requireArguments().getInt(ARG_CURRENT_SOURCE_START)
    }

    private val readerViewModel: NovelReaderV3ViewModel by lazy {
        ViewModelProvider(requireParentFragment())[NovelReaderV3ViewModel::class.java]
    }

    private var listView: RecyclerView? = null
    private var currentIndex: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetReaderChaptersBinding.inflate(inflater, container, false)
        val chapters = readerViewModel.getChapterOutline()
        binding.title.text = getString(R.string.chapters_title)
        binding.count.text = getString(R.string.chapters_count, chapters.size)
        currentIndex = resolveCurrentIndex(chapters)
        val accent = ceui.lisa.utils.Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = ChapterAdapter(chapters, currentIndex, accent) { entry ->
            (parentFragment as? ChapterSheetCallback)?.onChapterSelected(entry)
            dismissAllowingStateLoss()
        }
        listView = binding.list
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
        listView?.post {
            val lm = listView?.layoutManager as? LinearLayoutManager ?: return@post
            if (currentIndex >= 0) lm.scrollToPositionWithOffset(currentIndex, 0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        listView = null
    }

    private fun resolveCurrentIndex(chapters: List<ChapterOutlineEntry>): Int {
        if (chapters.isEmpty()) return -1
        for (i in chapters.indices) {
            val here = chapters[i]
            val next = chapters.getOrNull(i + 1)
            if (currentSourceStart >= here.sourceStart && (next == null || currentSourceStart < next.sourceStart)) {
                return i
            }
        }
        return 0
    }

    private class ChapterAdapter(
        private val entries: List<ChapterOutlineEntry>,
        private val currentIndex: Int,
        private val accent: Int,
        private val onClick: (ChapterOutlineEntry) -> Unit,
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderChapterRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val isCurrent = position == currentIndex
            val textColor = if (isCurrent) accent
                else ContextCompat.getColor(holder.itemView.context, R.color.v3_text_1)
            holder.binding.chapterTitle.text = entry.title
            holder.binding.chapterTitle.setTextColor(textColor)
            holder.binding.chapterTitle.setTypeface(
                holder.binding.chapterTitle.typeface,
                if (isCurrent) Typeface.BOLD else Typeface.NORMAL,
            )
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        class VH(val binding: ItemReaderChapterRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "ChapterListSheet"
        private const val ARG_CURRENT_SOURCE_START = "current_source_start"

        fun newInstance(currentSourceStart: Int) = ChapterListSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_CURRENT_SOURCE_START, currentSourceStart)
            }
        }
    }
}

/**
 * 让 Material BottomSheet 容器背景透明，使布局自身的圆角背景可见。
 */
internal object ReaderSheetUi {

    fun applyExpandedHeight(fragment: BottomSheetDialogFragment, fraction: Float = 0.7f) {
        val dialog = fragment.dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(Color.TRANSPARENT)
        val displayHeight = fragment.resources.displayMetrics.heightPixels
        val targetHeight = (displayHeight * fraction).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { height = targetHeight }
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = targetHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    fun applyTransparentBackground(fragment: BottomSheetDialogFragment) {
        val dialog = fragment.dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(Color.TRANSPARENT)
    }
}
