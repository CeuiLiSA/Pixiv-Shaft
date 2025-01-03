package ceui.pixiv.ui.novel

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelSeriesHeaderBinding
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class NovelSeriesHeaderHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long {
        return series.id
    }
}

@ItemHolder(NovelSeriesHeaderHolder::class)
class NovelSeriesHeaderViewHolder(bd: CellNovelSeriesHeaderBinding) : ListItemViewHolder<CellNovelSeriesHeaderBinding, NovelSeriesHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.series = holder.series
        if (holder.series.caption?.isNotEmpty() == true) {
            binding.caption.isVisible = true
            binding.caption.text = HtmlCompat.fromHtml(holder.series.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
        } else {
            binding.caption.isVisible = false
        }
    }
}

interface NovelSeriesHeaderActionReceiver {
}