package ceui.pixiv.ui.common

import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.loxia.DateParse
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
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.root.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel.id)
        }
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novel.id)
        }
        binding.publishTime.text = context.getString(
            R.string.published_on,
            DateParse.getTimeAgo(context, holder.novel.create_date)
        )
        binding.textCount.text =
            context.getString(R.string.how_many_words, holder.novel.text_length.toString())
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novelId: Long)
    fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long)
}