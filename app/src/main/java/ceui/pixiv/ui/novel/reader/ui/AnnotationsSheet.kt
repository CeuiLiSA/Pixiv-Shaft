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
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.databinding.ItemReaderAnnotationRowBinding
import ceui.lisa.databinding.SheetReaderAnnotationsBinding
import ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

interface AnnotationSheetCallback {
    fun onJumpToAnnotation(entry: NovelAnnotationEntity)
    fun onEditAnnotation(entry: NovelAnnotationEntity)
    fun onDeleteAnnotation(entry: NovelAnnotationEntity)
}

class AnnotationsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReaderAnnotationsBinding? = null
    private val binding get() = _binding!!

    private val readerViewModel: NovelReaderV3ViewModel by lazy {
        ViewModelProvider(requireParentFragment())[NovelReaderV3ViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetReaderAnnotationsBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.annotations_title)
        binding.list.layoutManager = LinearLayoutManager(requireContext())

        readerViewModel.annotations.observe(viewLifecycleOwner) { entries ->
            val list = entries.orEmpty()
            binding.count.text = getString(R.string.annotations_count, list.size)
            if (list.isEmpty()) {
                binding.empty.text = getString(R.string.annotations_empty)
                binding.empty.isVisible = true
                binding.list.isVisible = false
            } else {
                binding.empty.isVisible = false
                binding.list.isVisible = true
                binding.list.adapter = Adapter(list, parentFragment as? AnnotationSheetCallback) {
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
        private val entries: List<NovelAnnotationEntity>,
        private val callback: AnnotationSheetCallback?,
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
                callback?.onJumpToAnnotation(entry)
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
                            0 -> callback?.onEditAnnotation(entry)
                            1 -> callback?.onDeleteAnnotation(entry)
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
