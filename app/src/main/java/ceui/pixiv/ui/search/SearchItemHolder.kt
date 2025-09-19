package ceui.pixiv.ui.search

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSearchItemBinding
import ceui.loxia.Tag
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

class SearchItemHolder(val tag: Tag) : ListItemHolder() {
    override fun getItemId(): Long {
        return tag.hashCode().toLong()
    }
}

@ItemHolder(SearchItemHolder::class)
class SearchItemViewHolder(private val bd: CellSearchItemBinding) :
    ListItemViewHolder<CellSearchItemBinding, SearchItemHolder>(bd) {

    override fun onBindViewHolder(holder: SearchItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        if (holder.tag.translated_name?.isNotEmpty() == true) {
            binding.main.text = holder.tag.translated_name
            binding.second.text = "#${holder.tag.name}"
        } else {
            binding.main.text = holder.tag.name
            binding.second.text = "#${holder.tag.name}"
        }
        binding.itemRoot.setOnClick {
            it.findActionReceiverOrNull<SearchItemActionReceiver>()?.onClickSearchItem(holder.tag)
        }
    }
}

interface SearchItemActionReceiver {
    fun onClickSearchItem(tag: Tag)
}