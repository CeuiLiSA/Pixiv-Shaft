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

    /** FragmentManager 会恢复 tag，宿主可据此区分详情导出和列表卡片下载。 */
    fun onExportFormatChosen(format: ExportFormat, sourceTag: String?) {
        onExportFormatChosen(format)
    }
}

class ExportSheet : BottomSheetDialogFragment() {

    // edgeToEdge:让 window 画到导航栏底下,内容背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = SheetReaderExportBinding.inflate(inflater, container, false)
        // \u7528\u5168\u5F69 emoji\uFF0C\u522B\u7528 \uD83C\uDD63/\uD83C\uDD5C \u8FD9\u7C7B\u300Cnegative circled\u300D\u5355\u8272\u5B57\u5F62 \u2014\u2014 \u5B83\u4EEC\u5728\u6DF1\u8272\u80CC\u666F\u4E0A
        // \u6E32\u67D3\u6210\u9ED1\u8272,\u9ED1\u6697\u6A21\u5F0F\u4E0B\u770B\u4E0D\u89C1(EPUB \uD83D\uDCD6 / PDF \uD83D\uDCC4 \u662F\u5F69\u8272 emoji \u6240\u4EE5\u4E00\u76F4\u53EF\u89C1)\u3002
        val rows = listOf(
            Triple(ExportFormat.Txt, getString(R.string.export_txt_desc), "\uD83D\uDCCB"),      // \uD83D\uDCCB \u526A\u8D34\u677F(\u8D34\u5230\u4EFB\u4F55\u5730\u65B9)
            Triple(ExportFormat.Markdown, getString(R.string.export_md_desc), "\uD83D\uDCDD"),   // \uD83D\uDCDD \u5907\u5FD8(\u6807\u8BB0\u8BED\u8A00)
            Triple(ExportFormat.Epub, getString(R.string.export_epub_desc), "\uD83D\uDCD6"),     // \uD83D\uDCD6
            Triple(ExportFormat.Pdf, getString(R.string.export_pdf_desc), "\uD83D\uDCC4"),       // \uD83D\uDCC4
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = Adapter(rows) { format ->
            (parentFragment as? ExportFormatCallback)?.onExportFormatChosen(format, tag)
            dismissAllowingStateLoss()
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // applyTransparentBackground 内部已顺带把 sheet 背景铺到屏幕底(letDrawBehindNavBar),
        // 消除底部 nav bar 那条透明缝/黑条。
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
