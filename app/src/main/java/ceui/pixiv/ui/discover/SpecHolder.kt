package ceui.pixiv.ui.discover

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellHomeSpecBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class SpecHolder(val title: String) : ListItemHolder() {
    override fun getItemId(): Long {
        return title.hashCode().toLong()
    }
}

@ItemHolder(SpecHolder::class)
class SpecViewHolder(bd: CellHomeSpecBinding) : ListItemViewHolder<CellHomeSpecBinding, SpecHolder>(bd) {

    override fun onBindViewHolder(holder: SpecHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.contentText.text = holder.title
    }
}
