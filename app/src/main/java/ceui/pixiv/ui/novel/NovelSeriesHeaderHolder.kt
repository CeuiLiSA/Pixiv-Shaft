package ceui.pixiv.ui.novel

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelSeriesHeaderBinding
import ceui.lisa.utils.Common
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

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
        val rawCaption = holder.series.caption.orEmpty()
        if (rawCaption.isNotEmpty()) {
            binding.caption.isVisible = true
            // Pixiv 的 series caption 经常混用裸 `\n` 和 `<br>`，HtmlCompat 只认后者，
            // 不做替换就会把几十段压成一整段（issue 里的排版投诉）。先把 `\n` 改成 `<br>`
            // 再交给 HtmlCompat 解析。
            val normalized = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            binding.caption.text = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
            // 点简介 = 复制简介（纯文本）。
            binding.caption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(it.context, plain)
            }
        } else {
            binding.caption.isVisible = false
        }
        binding.title.setOnClick {
            Common.copy(it.context, holder.series.title)
        }
    }
}

interface NovelSeriesHeaderActionReceiver {
}
