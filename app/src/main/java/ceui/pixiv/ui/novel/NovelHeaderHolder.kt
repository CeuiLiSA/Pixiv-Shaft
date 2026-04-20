package ceui.pixiv.ui.novel

import android.view.View
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.utils.Common
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.utils.setOnClick


class NovelHeaderHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelHeaderHolder::class)
class NovelHeaderViewHolder(bd: CellNovelHeaderBinding) : ListItemViewHolder<CellNovelHeaderBinding, NovelHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: NovelHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novelId)
        }
        binding.novelCover.setOnClick { sender ->
            liveNovel.value?.image_urls?.findMaxSizeUrl()?.let { url ->
                ImageUrlViewer.open(sender.context, url, "novel_${holder.novelId}_cover")
            }
        }
        binding.seriesName.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.title.setOnClick {
            Common.copy(context, liveNovel.value?.title)
        }
    }
}

interface NovelSeriesActionReceiver {
    fun onClickNovelSeries(sender: View, series: Series)
}