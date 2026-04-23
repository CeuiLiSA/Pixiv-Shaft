package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.database.NovelBookmarkEntity
import ceui.lisa.databinding.ItemReaderBookmarkRowBinding
import ceui.lisa.databinding.SheetReaderBookmarksBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BookmarksSheet : BottomSheetDialogFragment() {

    private var entries: List<NovelBookmarkEntity> = emptyList()
    private var onJumpTo: ((NovelBookmarkEntity) -> Unit)? = null
    private var onDelete: ((NovelBookmarkEntity) -> Unit)? = null

    fun configure(
        entries: List<NovelBookmarkEntity>,
        onJumpTo: (NovelBookmarkEntity) -> Unit,
        onDelete: (NovelBookmarkEntity) -> Unit,
    ) {
        this.entries = entries
        this.onJumpTo = onJumpTo
        this.onDelete = onDelete
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = SheetReaderBookmarksBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.bookmarks_title)
        binding.count.text = getString(R.string.bookmarks_count, entries.size)
        if (entries.isEmpty()) {
            binding.empty.text = getString(R.string.bookmarks_empty)
            binding.empty.isVisible = true
            binding.list.isVisible = false
        } else {
            binding.list.layoutManager = LinearLayoutManager(requireContext())
            binding.list.adapter = Adapter(entries, onJumpTo, onDelete) { dismissAllowingStateLoss() }
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyTransparentBackground(this)
    }

    private class Adapter(
        private val entries: List<NovelBookmarkEntity>,
        private val onJumpTo: ((NovelBookmarkEntity) -> Unit)?,
        private val onDelete: ((NovelBookmarkEntity) -> Unit)?,
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
                onJumpTo?.invoke(entry)
                dismiss()
            }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.bookmarks_delete_confirm)
                    .setPositiveButton(R.string.action_delete) { _, _ -> onDelete?.invoke(entry) }
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
