package ceui.pixiv.ui.detail

import android.annotation.SuppressLint
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellGifBinding
import ceui.loxia.Illust
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.task.GifState
import ceui.pixiv.utils.screenWidth
import com.bumptech.glide.Glide
import timber.log.Timber
import kotlin.math.roundToInt

class GifHolder(
    val illust: Illust,
    val gifState: LiveData<GifState>,
) : ListItemHolder() {
}

@ItemHolder(GifHolder::class)
class GifViewHolder(private val bd: CellGifBinding) :
    ListItemViewHolder<CellGifBinding, GifHolder>(bd) {

    @SuppressLint("SetTextI18n")
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

        holder.gifState.observe(lifecycleOwner) { state ->
            if (state is GifState.FetchGifResponse || state is GifState.Encode) {
                progressCircular.isVisible = true
                progressCircular.isIndeterminate = true
                progressCircular.show()

                binding.stateLabel.text = if (state is GifState.FetchGifResponse) {
                    "Fetching"
                } else {
                    "Encoding"
                }
            } else if (state is GifState.DownloadZip) {
                progressCircular.isIndeterminate = false
                progressCircular.max = 100
                val percentage = state.progress
                progressCircular.isVisible = true
//                progressCircular.setProgress(percentage, true)
                progressCircular.progress = percentage
                binding.stateLabel.text = "Downloading"
            } else if (state is GifState.Done) {
                progressCircular.isVisible = false
                Glide.with(binding.image).load(state.webpFile).into(binding.image)
            }
            binding.stateLabel.isVisible = state !is GifState.Done

            Timber.d("sadsadasw2 gifState: ${state}")
        }
    }
}

