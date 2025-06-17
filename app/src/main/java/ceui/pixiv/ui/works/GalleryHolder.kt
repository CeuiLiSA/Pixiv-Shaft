package ceui.pixiv.ui.works

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellGalleryBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.getImageDimensions
import ceui.pixiv.ui.common.setUpWithTaskStatus
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenWidth
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage
import timber.log.Timber
import kotlin.math.roundToInt

class GalleryHolder(
    val illust: Illust,
    val index: Int,
    val loadTask: LoadTask,
    val loadUrl: () -> Unit
) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return index == (other as? GalleryHolder)?.index
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return index == (other as? GalleryHolder)?.index
    }
}

@ItemHolder(GalleryHolder::class)
class GalleryViewHolder(bd: CellGalleryBinding) :
    ListItemViewHolder<CellGalleryBinding, GalleryHolder>(bd) {

    override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        // 清除上一次的图片，避免复用时闪现旧图
        binding.image.setImageDrawable(null)
        binding.image.setImageBitmap(null)
        holder.loadUrl()

        fun resize(resolution: Pair<Int, Int>) {
            Timber.d("resizeRatio: ${resolution.second.toFloat() / resolution.first.toFloat()}")
            val imgHeight =
                (screenWidth * resolution.second / resolution.first.toFloat()).roundToInt()
            binding.image.updateLayoutParams {
                width = screenWidth
                height = imgHeight
            }
        }

        if (holder.index == 0) {
            resize(Pair(holder.illust.width, holder.illust.height))
            Glide.with(context).load(GlideUrlChild(holder.illust.image_urls?.large))
                .into(binding.image)
        } else {
            resize(Pair(screenWidth, 300.ppppx))
        }

        holder.loadTask.result.removeObservers(lifecycleOwner)
        holder.loadTask.result.observe(lifecycleOwner) { file ->
            val resolution = getImageDimensions(file)
            resize(resolution)
            binding.image.loadImage(file) { }
        }
        binding.progressCircular.setUpWithTaskStatus(
            holder.loadTask.status,
            binding.errorFrame,
            binding.emptyTitle,
            binding.errorRetryButton,
            holder.loadUrl,
            lifecycleOwner
        )

        binding.image.setOnClick {
            it.findActionReceiverOrNull<GalleryActionReceiver>()
                ?.onClickGalleryHolder(holder.index, holder)
        }
    }
}

interface GalleryActionReceiver {
    fun onClickGalleryHolder(index: Int, galleryHolder: GalleryHolder)
}