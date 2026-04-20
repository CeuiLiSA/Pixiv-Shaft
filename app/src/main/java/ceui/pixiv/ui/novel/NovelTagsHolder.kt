package ceui.pixiv.ui.novel

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelTagsBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class NovelTagsHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelTagsHolder::class)
class NovelTagsViewHolder(bd: CellNovelTagsBinding) :
    ListItemViewHolder<CellNovelTagsBinding, NovelTagsHolder>(bd) {

    override fun onBindViewHolder(holder: NovelTagsHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
    }
}
