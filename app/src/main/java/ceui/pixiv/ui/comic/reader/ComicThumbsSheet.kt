package ceui.pixiv.ui.comic.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellComicThumbBinding
import ceui.lisa.databinding.SheetComicThumbsBinding
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.widgets.PixivBottomSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

/**
 * Activity-scoped ViewModel：宿主 Fragment 进入 reader 时把当前 illust 的页面 URL 列表
 * 写进来；ThumbsSheet 通过 [activityViewModels] 拉取，避免把 200 条 URL 走 Bundle 引发
 * TransactionTooLargeException（与 #820 RecmdUserMap 同类问题）。
 */
class ComicReaderPagesProvider : ViewModel() {
    var pages: List<ComicReaderV3ViewModel.ComicPage> = emptyList()
    var currentIndex: Int = 0
}

class ComicThumbsSheet : PixivBottomSheet(R.layout.sheet_comic_thumbs) {

    private val binding by viewBinding(SheetComicThumbsBinding::bind)
    private val provider by activityViewModels<ComicReaderPagesProvider>()
    private val eventBus by activityViewModels<ComicReaderEventBus>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pages = provider.pages
        val current = provider.currentIndex
        if (pages.isEmpty()) { dismiss(); return }

        val span = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        binding.comicThumbsList.layoutManager = GridLayoutManager(requireContext(), span)
        binding.comicThumbsList.adapter = ThumbAdapter(pages, current) { idx ->
            eventBus.post(ComicReaderEventBus.Event.JumpToPage(idx))
            dismiss()
        }
        binding.comicThumbsList.scrollToPosition(current.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }

    private class ThumbAdapter(
        val pages: List<ComicReaderV3ViewModel.ComicPage>,
        val current: Int,
        val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ThumbAdapter.VH>() {

        class VH(val b: CellComicThumbBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(CellComicThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val page = pages[position]
            val ctx = holder.b.root.context
            holder.b.cellThumbIndex.text = ctx.getString(
                R.string.comic_reader_page_indicator, position + 1, pages.size,
            )
            holder.b.cellThumbImage.alpha = if (position == current) 1f else 0.85f
            val glideUrl = GlideUrl(
                page.previewUrl,
                LazyHeaders.Builder().addHeader("Referer", "https://app-api.pixiv.net/").build(),
            )
            Glide.with(holder.b.cellThumbImage).load(glideUrl).into(holder.b.cellThumbImage)
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }

    companion object { const val TAG = "ComicThumbsSheet" }
}
