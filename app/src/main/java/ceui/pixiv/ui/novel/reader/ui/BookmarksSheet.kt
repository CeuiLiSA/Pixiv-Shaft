package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.database.NovelBookmarkEntity
import ceui.lisa.databinding.ItemReaderBookmarkRowBinding
import ceui.lisa.databinding.SheetReaderBookmarksBinding
import ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

interface BookmarkSheetCallback {
    fun onJumpToBookmark(entry: NovelBookmarkEntity)
    fun onDeleteBookmark(entry: NovelBookmarkEntity)
}

class BookmarksSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReaderBookmarksBinding? = null
    private val binding get() = _binding!!

    private val readerViewModel: NovelReaderV3ViewModel by lazy {
        ViewModelProvider(requireParentFragment())[NovelReaderV3ViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetReaderBookmarksBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.bookmarks_title)
        binding.list.layoutManager = LinearLayoutManager(requireContext())

        readerViewModel.bookmarks.observe(viewLifecycleOwner) { entries ->
            val list = entries.orEmpty()
            binding.count.text = getString(R.string.bookmarks_count, list.size)
            if (list.isEmpty()) {
                binding.empty.text = getString(R.string.bookmarks_empty)
                binding.empty.isVisible = true
                binding.list.isVisible = false
            } else {
                binding.empty.isVisible = false
                binding.list.isVisible = true
                binding.list.adapter = Adapter(list, parentFragment as? BookmarkSheetCallback) {
                    dismissAllowingStateLoss()
                }
            }
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyTransparentBackground(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class Adapter(
        private val entries: List<NovelBookmarkEntity>,
        private val callback: BookmarkSheetCallback?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderBookmarkRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val ctx = holder.itemView.context
            holder.binding.preview.text = entry.preview.ifEmpty {
                ctx.getString(R.string.bookmarks_page_format, entry.pageIndex + 1)
            }
            holder.binding.footer.text = "${ctx.getString(R.string.bookmarks_page_format, entry.pageIndex + 1)} \u00b7 ${DateFormat.format("yyyy-MM-dd HH:mm", entry.createdTime)}"
            holder.itemView.setOnClickListener {
                callback?.onJumpToBookmark(entry)
                dismiss()
            }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.bookmarks_delete_confirm)
                    .setPositiveButton(R.string.action_delete) { _, _ -> callback?.onDeleteBookmark(entry) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                true
            }
        }

        class VH(val binding: ItemReaderBookmarkRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "BookmarksSheet"
    }
}
