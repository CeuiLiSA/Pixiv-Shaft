package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellGifBinding
import ceui.lisa.models.GifResponse
import ceui.loxia.Illust
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import java.io.File

data class GifPack(
    val gifResponse: GifResponse,
    val unzipFolder: File,
)

class GifHolder(val illust: Illust, val gifPackLiveData: LiveData<GifPack>) : ListItemHolder() {
}

@ItemHolder(GifHolder::class)
class GifViewHolder(private val bd: CellGifBinding) :
    ListItemViewHolder<CellGifBinding, GifHolder>(bd) {

    override fun onBindViewHolder(holder: GifHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        holder.gifPackLiveData.observe(lifecycleOwner) { gifPack ->
            binding.folderPath.text = gifPack.unzipFolder.path
        }
    }
}

