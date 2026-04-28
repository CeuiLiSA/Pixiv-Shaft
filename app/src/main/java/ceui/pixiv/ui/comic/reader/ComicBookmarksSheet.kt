package ceui.pixiv.ui.comic.reader

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.database.ComicBookmarkEntity
import ceui.lisa.databinding.CellComicBookmarkBinding
import ceui.lisa.databinding.SheetComicBookmarksBinding
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.widgets.PixivBottomSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

class ComicBookmarksSheet : PixivBottomSheet(R.layout.sheet_comic_bookmarks) {

    private val binding by viewBinding(SheetComicBookmarksBinding::bind)
    private val eventBus by activityViewModels<ComicReaderEventBus>()
    private val repo by lazy { ComicBookmarkRepository.fromContext() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val illustId = arguments?.getLong(ARG_ILLUST_ID, 0L) ?: 0L

        val adapter = BookmarkAdapter(
            onJump = { entry ->
                eventBus.post(ComicReaderEventBus.Event.JumpToBookmark(entry))
                dismiss()
            },
            onDelete = { entry -> repo.delete(entry.bookmarkId) },
        )
        binding.comicBookmarksList.layoutManager = LinearLayoutManager(requireContext())
        binding.comicBookmarksList.adapter = adapter

        binding.comicBookmarksAdd.setOnClickListener {
            eventBus.post(ComicReaderEventBus.Event.AddBookmarkAtCurrent)
            dismiss()
        }

        repo.observeFor(illustId).observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.comicBookmarksEmpty.isVisible = list.isEmpty()
        }
    }

    private class BookmarkAdapter(
        val onJump: (ComicBookmarkEntity) -> Unit,
        val onDelete: (ComicBookmarkEntity) -> Unit,
    ) : ListAdapter<ComicBookmarkEntity, BookmarkAdapter.VH>(DIFF) {

        class VH(val b: CellComicBookmarkBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = CellComicBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = getItem(position)
            val ctx = holder.b.root.context
            val total = entry.totalPages.coerceAtLeast(1)
            holder.b.cellBookmarkTitle.text = ctx.getString(
                R.string.comic_reader_page_indicator, entry.pageIndex + 1, total
            )
            holder.b.cellBookmarkNote.text = entry.note
            holder.b.cellBookmarkNote.isVisible = entry.note.isNotEmpty()
            holder.b.cellBookmarkTime.text = DateUtils.getRelativeTimeSpanString(entry.createdTime)
            if (entry.previewUrl.isNotEmpty()) {
                val glideUrl = GlideUrl(
                    entry.previewUrl,
                    LazyHeaders.Builder().addHeader("Referer", "https://app-api.pixiv.net/").build(),
                )
                Glide.with(holder.b.cellBookmarkThumb).load(glideUrl).into(holder.b.cellBookmarkThumb)
            }
            holder.b.root.setOnClickListener { onJump(entry) }
            holder.b.cellBookmarkDelete.setOnClickListener { onDelete(entry) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ComicBookmarkEntity>() {
                override fun areItemsTheSame(a: ComicBookmarkEntity, b: ComicBookmarkEntity) = a.bookmarkId == b.bookmarkId
                override fun areContentsTheSame(a: ComicBookmarkEntity, b: ComicBookmarkEntity) =
                    a.bookmarkId == b.bookmarkId && a.pageIndex == b.pageIndex && a.note == b.note && a.previewUrl == b.previewUrl
            }
        }
    }

    companion object {
        const val TAG = "ComicBookmarksSheet"
        private const val ARG_ILLUST_ID = "illust_id"

        fun newInstance(illustId: Long) = ComicBookmarksSheet().apply {
            arguments = Bundle().apply { putLong(ARG_ILLUST_ID, illustId) }
        }
    }
}
