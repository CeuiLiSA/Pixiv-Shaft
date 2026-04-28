package ceui.pixiv.ui.comic.reader

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellComicPageBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import timber.log.Timber

/**
 * RecyclerView/ViewPager2 共用的页适配器。每个 page 一个 ComicZoomImageView。
 * 由调用者在 onBindViewHolder 时把 onSingleTap 传进来（chrome toggle）。
 */
class ComicPagerAdapter(
    private val onSingleTap: (TapZone) -> Unit,
    private val onLongPressPage: ((Int) -> Unit)? = null,
) : ListAdapter<ComicReaderV3ViewModel.ComicPage, ComicPagerAdapter.PageHolder>(DIFF) {

    enum class TapZone { Left, Center, Right }

    var fillHeight: Boolean = false  // webtoon 模式下 ImageView 用 wrap_content；横翻时铺满

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = CellComicPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (fillHeight) {
            binding.image.layoutParams = binding.image.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else {
            binding.image.layoutParams = binding.image.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        return PageHolder(binding, onSingleTap, onLongPressPage)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PageHolder) {
        Glide.with(holder.binding.image).clear(holder.binding.image)
    }

    class PageHolder(
        val binding: CellComicPageBinding,
        private val onSingleTap: (TapZone) -> Unit,
        private val onLongPressPage: ((Int) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.image.onSingleTap = { e ->
                val w = binding.image.width
                val zone = when {
                    w <= 0 -> TapZone.Center
                    e.x < w / 3f -> TapZone.Left
                    e.x > w * 2f / 3f -> TapZone.Right
                    else -> TapZone.Center
                }
                onSingleTap(zone)
            }
            binding.image.onLongPress = { _ ->
                val current = binding.root.tag as? ComicReaderV3ViewModel.ComicPage
                if (current != null) onLongPressPage?.invoke(current.index)
            }
            binding.reload.setOnClickListener {
                val current = binding.root.tag as? ComicReaderV3ViewModel.ComicPage ?: return@setOnClickListener
                bind(current)
            }
        }

        fun bind(page: ComicReaderV3ViewModel.ComicPage) {
            binding.root.tag = page
            binding.reload.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE
            val ctx = binding.image.context
            val url = if (ComicReaderSettings.loadOriginal) page.originalUrl else page.previewUrl
            val glideUrl = GlideUrl(
                url,
                LazyHeaders.Builder().addHeader("Referer", "https://app-api.pixiv.net/").build()
            )
            Glide.with(ctx)
                .load(glideUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        Timber.tag("ComicReaderV3").w(e, "page load failed url=${url.takeLast(40)}")
                        binding.progress.visibility = View.GONE
                        binding.reload.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        binding.progress.visibility = View.GONE
                        binding.reload.visibility = View.GONE
                        return false
                    }
                })
                .into(binding.image)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ComicReaderV3ViewModel.ComicPage>() {
            override fun areItemsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a.index == b.index
            override fun areContentsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a == b
        }
    }
}
