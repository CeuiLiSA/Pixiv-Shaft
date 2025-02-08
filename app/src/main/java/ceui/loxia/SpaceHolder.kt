package ceui.loxia

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSpaceBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.ppppx

class SpaceHolder(val limitedHeight: Int = 40.ppppx) : ListItemHolder() {
}

@ItemHolder(SpaceHolder::class)
class SpaceViewHolder(private val bd: CellSpaceBinding) : ListItemViewHolder<CellSpaceBinding, SpaceHolder>(bd) {

    override fun onBindViewHolder(holder: SpaceHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.rootLayout.updateLayoutParams {
            height = holder.limitedHeight
        }
    }
}