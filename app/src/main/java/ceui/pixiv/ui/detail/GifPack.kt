package ceui.pixiv.ui.detail

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellGifBinding
import ceui.lisa.models.GifResponse
import ceui.loxia.Illust
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.screenWidth
import com.bumptech.glide.Glide
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

data class GifPack(
    val gifResponse: GifResponse,
    val webpFile: File,
)

class GifHolder(
    val illust: Illust,
    val progressLiveData: LiveData<Int>,
    val gifPackLiveData: LiveData<GifPack>
) : ListItemHolder() {
}

@ItemHolder(GifHolder::class)
class GifViewHolder(private val bd: CellGifBinding) :
    ListItemViewHolder<CellGifBinding, GifHolder>(bd) {

    override fun onBindViewHolder(holder: GifHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        fun resize(resolution: Pair<Int, Int>) {
            Timber.d("resizeRatio: ${resolution.second.toFloat() / resolution.first.toFloat()}")
            val imgHeight =
                (screenWidth * resolution.second / resolution.first.toFloat()).roundToInt()
            binding.image.updateLayoutParams {
                width = screenWidth
                height = imgHeight
            }
        }
        resize(Pair(holder.illust.width, holder.illust.height))

        val progressCircular = binding.progressCircular

        holder.progressLiveData.observe(lifecycleOwner) { percentage ->
            if (percentage == 100) {
                progressCircular.isVisible = false
            } else {
                progressCircular.isVisible = true
                progressCircular.setProgress(percentage, true)
            }

            Timber.d("sadsadasw2 percentage: ${percentage}")
        }

        holder.gifPackLiveData.observe(lifecycleOwner) { gifPack ->
            Glide.with(binding.image).load(gifPack.webpFile).into(binding.image)
        }
    }
}

