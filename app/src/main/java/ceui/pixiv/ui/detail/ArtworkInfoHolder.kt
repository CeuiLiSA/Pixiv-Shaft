package ceui.pixiv.ui.detail

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkInfoBinding
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick


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
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(it, holder.illustId)
        }
    }
}
