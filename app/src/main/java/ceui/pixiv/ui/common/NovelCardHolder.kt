package ceui.pixiv.ui.common

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.novel.NovelSeriesActionReceiver
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick

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
        binding.novel = ObjectPool.get<Novel>(holder.novel.id)
        binding.userLayout.setOnClick { sender ->
            holder.novel.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
        binding.seriesName.setOnClick { sender ->
            holder.novel.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickSeries(sender, series)
            }
        }
        binding.root.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel.id)
        }
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novel.id)
        }
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novelId: Long)
    fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long)
}