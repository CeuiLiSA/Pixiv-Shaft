package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.lisa.databinding.CellPvisionMiniCardBinding
import ceui.loxia.Article
import ceui.loxia.Novel

class PvisionMiniCardHolder(val article: Article) : ListItemHolder() {
}

@ItemHolder(PvisionMiniCardHolder::class)
class PvisionMiniCardViewHolder(bd: CellPvisionMiniCardBinding) : ListItemViewHolder<CellPvisionMiniCardBinding, PvisionMiniCardHolder>(bd) {

    override fun onBindViewHolder(holder: PvisionMiniCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
    }
}