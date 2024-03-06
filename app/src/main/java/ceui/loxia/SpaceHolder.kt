package ceui.loxia

import ceui.lisa.databinding.CellSpaceBinding
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder

class SpaceHolder : ListItemHolder() {
}

class SpaceViewHolder(private val bd: CellSpaceBinding) : ListItemViewHolder<CellSpaceBinding, SpaceHolder>(bd) {

    override fun onBindViewHolder(holder: SpaceHolder, position: Int) {
        super.onBindViewHolder(holder, position)
    }
}