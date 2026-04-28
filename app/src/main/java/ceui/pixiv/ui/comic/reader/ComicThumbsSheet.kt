package ceui.pixiv.ui.comic.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

interface ComicThumbsCallback {
    fun onPagePicked(index: Int)
}

class ComicThumbsSheet : PixivBottomSheet(R.layout.sheet_comic_thumbs) {

    private val binding by viewBinding(SheetComicThumbsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cb = parentFragment as? ComicThumbsCallback
        @Suppress("UNCHECKED_CAST")
        val urls: List<String> = (arguments?.getStringArrayList(ARG_URLS) ?: arrayListOf()) as ArrayList<String>
        val current = arguments?.getInt(ARG_CURRENT, 0) ?: 0

        val span = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        binding.comicThumbsList.layoutManager = GridLayoutManager(requireContext(), span)
        binding.comicThumbsList.adapter = ThumbAdapter(urls, current) { idx ->
            cb?.onPagePicked(idx); dismiss()
        }
        binding.comicThumbsList.scrollToPosition(current.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
    }

    private class ThumbAdapter(
        val urls: List<String>,
        val current: Int,
        val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ThumbAdapter.VH>() {

        class VH(val b: CellComicThumbBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(CellComicThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = urls.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.b.root.context
            holder.b.cellThumbIndex.text = ctx.getString(
                R.string.comic_reader_page_indicator, position + 1, urls.size,
            )
            holder.b.cellThumbImage.alpha = if (position == current) 1f else 0.85f
            val glideUrl = GlideUrl(
                urls[position],
                LazyHeaders.Builder().addHeader("Referer", "https://app-api.pixiv.net/").build(),
            )
            Glide.with(holder.b.cellThumbImage).load(glideUrl).into(holder.b.cellThumbImage)
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }

    companion object {
        const val TAG = "ComicThumbsSheet"
        private const val ARG_URLS = "urls"
        private const val ARG_CURRENT = "current"

        fun newInstance(urls: List<String>, current: Int) = ComicThumbsSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_URLS, ArrayList(urls))
                putInt(ARG_CURRENT, current)
            }
        }
    }
}
