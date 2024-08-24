package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTabBinding

class TabCellHolder(
    val title: String
) : ListItemHolder() {

}

@ItemHolder(TabCellHolder::class)
class TabCellViewHolder(bd: CellTabBinding) : ListItemViewHolder<CellTabBinding, TabCellHolder>(bd) {

    override fun onBindViewHolder(holder: TabCellHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
    }
}