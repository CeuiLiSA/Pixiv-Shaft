package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderExportRowBinding
import ceui.lisa.databinding.SheetReaderExportBinding
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

interface ExportFormatCallback {
    fun onExportFormatChosen(format: ExportFormat)
}

class ExportSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = SheetReaderExportBinding.inflate(inflater, container, false)
        val rows = listOf(
            Triple(ExportFormat.Txt, getString(R.string.export_txt_desc), "\uD83C\uDD63"),
            Triple(ExportFormat.Markdown, getString(R.string.export_md_desc), "\uD83C\uDD5C"),
            Triple(ExportFormat.Epub, getString(R.string.export_epub_desc), "\uD83D\uDCD6"),
            Triple(ExportFormat.Pdf, getString(R.string.export_pdf_desc), "\uD83D\uDCC4"),
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = Adapter(rows) { format ->
            (parentFragment as? ExportFormatCallback)?.onExportFormatChosen(format)
            dismissAllowingStateLoss()
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyTransparentBackground(this)
    }

    private class Adapter(
        private val rows: List<Triple<ExportFormat, String, String>>,
        private val onClick: (ExportFormat) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderExportRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (format, desc, emoji) = rows[position]
            holder.binding.icon.text = emoji
            holder.binding.formatTitle.text = holder.itemView.context.getString(format.displayNameResId)
            holder.binding.formatSubtitle.text = desc
            holder.itemView.setOnClickListener { onClick(format) }
        }

        class VH(val binding: ItemReaderExportRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "ExportSheet"
    }
}
