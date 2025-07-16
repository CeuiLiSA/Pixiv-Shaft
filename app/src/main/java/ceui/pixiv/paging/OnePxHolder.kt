package ceui.pixiv.paging

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemOnePxBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class OnePxHolder() : ListItemHolder()

@ItemHolder(OnePxHolder::class)
class OnePxViewHolder(bd: ItemOnePxBinding) :
    ListItemViewHolder<ItemOnePxBinding, OnePxHolder>(bd)