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
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction

interface BookmarkSheetCallback {
    fun onJumpToBookmark(entry: NovelBookmarkEntity)
    fun onDeleteBookmark(entry: NovelBookmarkEntity)
}

class BookmarksSheet : BottomSheetDialogFragment() {

    // edgeToEdge:让 window 画到导航栏底下,内容背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

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
                WitDialog.MessageDialogBuilder(ctx)
                    .setTitle(R.string.bookmarks_delete_confirm)
                    .addAction(R.string.action_cancel) { d, _ -> d.dismiss() }
                    .addAction(0, R.string.action_delete, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                        d.dismiss()
                        callback?.onDeleteBookmark(entry)
                    }
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
