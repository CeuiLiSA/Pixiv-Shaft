package ceui.pixiv.ui.user

import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellMineHeaderBinding
import ceui.lisa.databinding.CellTabBinding
import ceui.loxia.User
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class MineHeaderHolder(
    val liveUser: LiveData<User>
) : ListItemHolder() {

}

@ItemHolder(MineHeaderHolder::class)
class MineHeaderViewHolder(bd: CellMineHeaderBinding) : ListItemViewHolder<CellMineHeaderBinding, MineHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: MineHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
    }
}