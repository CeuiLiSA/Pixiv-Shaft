package ceui.pixiv.paging

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemOnePxBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class OnePxHolder() : ListItemHolder() {
    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return other is OnePxHolder
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is OnePxHolder
    }
}

@ItemHolder(OnePxHolder::class)
class OnePxViewHolder(bd: ItemOnePxBinding) :
    ListItemViewHolder<ItemOnePxBinding, OnePxHolder>(bd)