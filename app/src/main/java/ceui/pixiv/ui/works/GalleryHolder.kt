package ceui.pixiv.ui.works

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellGalleryBinding
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.getImageDimensions
import ceui.pixiv.ui.common.setUpWithTaskStatus
import ceui.pixiv.ui.task.LoadTask
import ceui.refactor.screenWidth
import ceui.refactor.setOnClick
import com.github.panpf.sketch.loadImage
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
        holder.loadUrl()
        lifecycleOwner?.let {
            holder.loadTask.result.observe(it) { file ->
                val resolution = getImageDimensions(file)
                val imgHeight =
                    (screenWidth * resolution.second / resolution.first.toFloat()).roundToInt()
                binding.resolution.text = "${resolution.first}x${resolution.second}"
                binding.image.updateLayoutParams {
                    width = screenWidth
                    height = imgHeight
                }
                binding.image.loadImage(file)
            }
            binding.progressCircular.setUpWithTaskStatus(
                holder.loadTask.status,
                binding.errorFrame,
                binding.emptyTitle,
                binding.errorRetryButton,
                holder.loadUrl,
                it
            )
        }

        binding.image.setOnClick {
            it.findActionReceiverOrNull<GalleryActionReceiver>()
                ?.onClickGalleryHolder(holder.index, holder)
        }
    }
}

interface GalleryActionReceiver {
    fun onClickGalleryHolder(index: Int, galleryHolder: GalleryHolder)
}