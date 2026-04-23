package ceui.pixiv.ui.novel

import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelSeriesHeaderBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat

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
        val series = holder.series
        val fmt = NumberFormat.getInstance()

        // Meta line
        binding.metaContentCount.text = context.getString(R.string.novel_meta_chapter_count, series.content_count)
        if (series.total_character_count > 0) {
            binding.metaCharCount.text = context.getString(
                R.string.novel_meta_word_count,
                fmt.format(series.total_character_count),
            )
            binding.metaCharCount.isVisible = true
            binding.metaDot2.isVisible = true
        } else {
            binding.metaCharCount.isVisible = false
            binding.metaDot2.isVisible = false
        }

        // Caption
        val rawCaption = series.caption.orEmpty()
        if (rawCaption.isNotEmpty()) {
            binding.caption.isVisible = true
            val normalized = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            binding.caption.text = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
            binding.caption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(it.context, plain)
            }
        } else {
            binding.caption.isVisible = false
        }

        binding.title.setOnClick {
            Common.copy(it.context, series.title)
        }

        bindInfoChips(series)
    }

    private fun bindInfoChips(series: NovelSeriesDetail) {
        chip(binding.chipSeriesId, R.string.novel_chip_series_id, series.id.toString(), series.id.toString())
        series.user?.let { user ->
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
            linkChip(binding.chipUserLink, R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
        } ?: run {
            binding.chipAuthor.isVisible = false
            binding.chipAuthorId.isVisible = false
            binding.chipUserLink.isVisible = false
        }
        if (series.content_count > 0) {
            chip(binding.chipContentCount, R.string.novel_chip_series_content_count,
                series.content_count.toString(), series.content_count.toString())
        } else {
            binding.chipContentCount.isVisible = false
        }
        if (series.total_character_count > 0) {
            chip(binding.chipCharCount, R.string.novel_chip_series_char_count,
                series.total_character_count.toString(), series.total_character_count.toString())
        } else {
            binding.chipCharCount.isVisible = false
        }
        linkChip(binding.chipSeriesLink, R.string.novel_chip_series_link,
            "https://www.pixiv.net/novel/series/${series.id}")
    }

    private fun chip(view: TextView, labelRes: Int, displayValue: String, copyValue: String) {
        val ctx = view.context
        view.text = ctx.getString(labelRes, displayValue)
        view.isVisible = true
        view.setOnClick { Common.copy(ctx, copyValue) }
    }

    private fun linkChip(view: TextView, labelRes: Int, url: String) {
        val ctx = view.context
        view.text = ctx.getString(labelRes)
        view.isVisible = true
        view.setOnClick { Common.copy(ctx, url) }
    }
}

interface NovelSeriesHeaderActionReceiver {
}
