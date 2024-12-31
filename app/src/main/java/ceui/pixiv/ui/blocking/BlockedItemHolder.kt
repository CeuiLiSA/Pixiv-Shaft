package ceui.pixiv.ui.blocking

import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellBlockedItemBinding
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.loxia.RefreshState
import ceui.loxia.setUpHolderRefreshState
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class BlockedItemHolder(val objectId: Long) : ListItemHolder() {
}

@ItemHolder(BlockedItemHolder::class)
class BlockedItemViewHolder(bd: CellBlockedItemBinding) : ListItemViewHolder<CellBlockedItemBinding, BlockedItemHolder>(bd) {

    override fun onBindViewHolder(holder: BlockedItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.itemId.text = holder.objectId.toString()
    }
}