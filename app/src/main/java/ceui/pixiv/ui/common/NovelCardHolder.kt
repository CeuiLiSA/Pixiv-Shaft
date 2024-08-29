package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.loxia.Novel
import ceui.loxia.findActionReceiverOrNull

class NovelCardHolder(val novel: Novel) : ListItemHolder() {
    override fun getItemId(): Long {
        return novel.id
    }
}

@ItemHolder(NovelCardHolder::class)
class NovelCardViewHolder(bd: CellNovelCardBinding) : ListItemViewHolder<CellNovelCardBinding, NovelCardHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel)
        }
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novel: Novel)
}