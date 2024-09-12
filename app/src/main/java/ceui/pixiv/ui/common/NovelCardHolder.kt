package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.refactor.setOnClick

class NovelCardHolder(val novel: Novel) : ListItemHolder() {
    init {
        ObjectPool.update(novel)
        novel.user?.let {
            ObjectPool.update(it)
        }
    }

    override fun getItemId(): Long {
        return novel.id
    }
}

@ItemHolder(NovelCardHolder::class)
class NovelCardViewHolder(bd: CellNovelCardBinding) : ListItemViewHolder<CellNovelCardBinding, NovelCardHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.userLayout.setOnClick { sender ->
            holder.novel.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
        binding.root.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel)
        }
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novel: Novel)
}