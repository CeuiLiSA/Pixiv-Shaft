package ceui.pixiv.ui.novel

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class NovelHeaderHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelHeaderHolder::class)
class NovelHeaderViewHolder(bd: CellNovelHeaderBinding) : ListItemViewHolder<CellNovelHeaderBinding, NovelHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: NovelHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.novel = ObjectPool.get<Novel>(holder.novelId)
    }
}