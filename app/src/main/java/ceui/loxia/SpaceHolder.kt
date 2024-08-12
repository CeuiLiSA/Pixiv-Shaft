package ceui.loxia

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSpaceBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class SpaceHolder : ListItemHolder() {
}

@ItemHolder(SpaceHolder::class)
class SpaceViewHolder(private val bd: CellSpaceBinding) : ListItemViewHolder<CellSpaceBinding, SpaceHolder>(bd) {

    override fun onBindViewHolder(holder: SpaceHolder, position: Int) {
        super.onBindViewHolder(holder, position)
    }
}