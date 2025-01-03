package ceui.pixiv.ui.novel

import android.view.View
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
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
        binding.seriesName.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickSeries(sender, series)
            }
        }
    }
}

interface NovelSeriesActionReceiver {
    fun onClickSeries(sender: View, series: Series)
}