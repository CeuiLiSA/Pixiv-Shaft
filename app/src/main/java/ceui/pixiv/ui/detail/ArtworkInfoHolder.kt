package ceui.pixiv.ui.detail

import androidx.core.text.HtmlCompat
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkInfoBinding
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class ArtworkInfoHolder(val illustId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return illustId
    }
}

@ItemHolder(ArtworkInfoHolder::class)
class ArtworkInfoViewHolder(bd: CellArtworkInfoBinding) : ListItemViewHolder<CellArtworkInfoBinding, ArtworkInfoHolder>(bd) {

    override fun onBindViewHolder(holder: ArtworkInfoHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveIllust = ObjectPool.get<Illust>(holder.illustId)
        binding.illust = liveIllust
        liveIllust.observe(lifecycleOwner) { illust ->
            if (illust.caption?.isNotEmpty() == true) {
                binding.caption.text = HtmlCompat.fromHtml(illust.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            }
        }
    }
}
