package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTabBinding

class TabCellHolder(
    val title: String,
    val secondaryTitle: String? = null,
    val extraInfo: String? = null,
    val showGreenDone: Boolean = false,
    val selected: LiveData<Boolean>? = null
) : ListItemHolder() {

}

@ItemHolder(TabCellHolder::class)
class TabCellViewHolder(bd: CellTabBinding) : ListItemViewHolder<CellTabBinding, TabCellHolder>(bd) {

    override fun onBindViewHolder(holder: TabCellHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
    }
}