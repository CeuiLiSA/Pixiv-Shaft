package ceui.loxia

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSpaceBinding
import ceui.lisa.databinding.CellTextDescBinding
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder

class TextDescHolder(val content: String) : ListItemHolder() {
}

@ItemHolder(TextDescHolder::class)
class TextDescViewHolder(private val bd: CellTextDescBinding) : ListItemViewHolder<CellTextDescBinding, TextDescHolder>(bd) {

    override fun onBindViewHolder(holder: TextDescHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.text.text = holder.content
    }
}