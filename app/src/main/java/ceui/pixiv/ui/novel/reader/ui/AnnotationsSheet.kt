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
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.databinding.ItemReaderAnnotationRowBinding
import ceui.lisa.databinding.SheetReaderAnnotationsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AnnotationsSheet : BottomSheetDialogFragment() {

    private var entries: List<NovelAnnotationEntity> = emptyList()
    private var onJumpTo: ((NovelAnnotationEntity) -> Unit)? = null
    private var onEdit: ((NovelAnnotationEntity) -> Unit)? = null
    private var onDelete: ((NovelAnnotationEntity) -> Unit)? = null

    fun configure(
        entries: List<NovelAnnotationEntity>,
        onJumpTo: (NovelAnnotationEntity) -> Unit,
        onEdit: (NovelAnnotationEntity) -> Unit,
        onDelete: (NovelAnnotationEntity) -> Unit,
    ) {
        this.entries = entries
        this.onJumpTo = onJumpTo
        this.onEdit = onEdit
        this.onDelete = onDelete
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = SheetReaderAnnotationsBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.annotations_title)
        binding.count.text = getString(R.string.annotations_count, entries.size)
        if (entries.isEmpty()) {
            binding.empty.text = getString(R.string.annotations_empty)
            binding.empty.isVisible = true
            binding.list.isVisible = false
        } else {
            binding.list.layoutManager = LinearLayoutManager(requireContext())
            binding.list.adapter = Adapter(entries, onJumpTo, onEdit, onDelete) { dismissAllowingStateLoss() }
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyTransparentBackground(this)
    }

    private class Adapter(
        private val entries: List<NovelAnnotationEntity>,
        private val onJumpTo: ((NovelAnnotationEntity) -> Unit)?,
        private val onEdit: ((NovelAnnotationEntity) -> Unit)?,
        private val onDelete: ((NovelAnnotationEntity) -> Unit)?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderAnnotationRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val ctx = holder.itemView.context
            holder.binding.colorChip.setBackgroundColor(entry.color)
            holder.binding.excerpt.text = "\u300C${entry.excerpt}\u300D"
            if (entry.note.isNotEmpty()) {
                holder.binding.note.isVisible = true
                holder.binding.note.text = "\uD83D\uDCDD ${entry.note}"
            } else {
                holder.binding.note.isVisible = false
            }
            holder.binding.footer.text = DateFormat.format("yyyy-MM-dd HH:mm", entry.updatedTime).toString()
            holder.itemView.setOnClickListener {
                onJumpTo?.invoke(entry)
                dismiss()
            }
            holder.itemView.setOnLongClickListener {
                val options = arrayOf(
                    if (entry.note.isEmpty()) ctx.getString(R.string.note_add_title) else ctx.getString(R.string.note_edit_title),
                    ctx.getString(R.string.action_delete),
                )
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(entry.excerpt.take(24))
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> onEdit?.invoke(entry)
                            1 -> onDelete?.invoke(entry)
                        }
                    }
                    .show()
                true
            }
        }

        class VH(val binding: ItemReaderAnnotationRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "AnnotationsSheet"
    }
}
