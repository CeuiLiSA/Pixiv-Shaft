package ceui.pixiv.ui.novel

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelSeriesCaptionBinding
import ceui.lisa.databinding.CellNovelSeriesHeroBinding
import ceui.lisa.databinding.CellNovelSeriesProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat

// ── 1. Hero: title + bookmark + meta ────────────────────────────────

class NovelSeriesHeroHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id
}

@ItemHolder(NovelSeriesHeroHolder::class)
class NovelSeriesHeroViewHolder(bd: CellNovelSeriesHeroBinding) :
    ListItemViewHolder<CellNovelSeriesHeroBinding, NovelSeriesHeroHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesHeroHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val series = holder.series
        val fmt = NumberFormat.getInstance()

        binding.title.text = series.title ?: ""
        binding.title.setOnClick { Common.copy(it.context, series.title) }

        // Bookmark
        binding.bookmark.setImageResource(
            if (series.watchlist_added == true) R.drawable.icon_liked else R.drawable.icon_not_liked
        )
        binding.bookmark.imageTintList = if (series.watchlist_added == true) {
            null
        } else {
            ColorStateList.valueOf(context.getColor(R.color.v3_text_3))
        }

        // Meta line
        binding.metaContentCount.text =
            context.getString(R.string.novel_meta_chapter_count, series.content_count)
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
    }
}

// ── 2. Caption ──────────────────────────────────────────────────────

class NovelSeriesCaptionHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id + 1_000_000
}

@ItemHolder(NovelSeriesCaptionHolder::class)
class NovelSeriesCaptionViewHolder(bd: CellNovelSeriesCaptionBinding) :
    ListItemViewHolder<CellNovelSeriesCaptionBinding, NovelSeriesCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val rawCaption = holder.series.caption.orEmpty()
        if (rawCaption.isNotEmpty()) {
            binding.caption.isVisible = true
            val normalized = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            binding.caption.text =
                HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
            binding.caption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(it.context, plain)
            }
        } else {
            binding.caption.isVisible = false
        }
    }
}

// ── 3. Profile chips ────────────────────────────────────────────────

class NovelSeriesProfileHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id + 2_000_000
}

@ItemHolder(NovelSeriesProfileHolder::class)
class NovelSeriesProfileViewHolder(bd: CellNovelSeriesProfileBinding) :
    ListItemViewHolder<CellNovelSeriesProfileBinding, NovelSeriesProfileHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesProfileHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        bindInfoChips(holder.series)
    }

    private fun bindInfoChips(series: NovelSeriesDetail) {
        chip(binding.chipSeriesId, R.string.novel_chip_series_id,
            series.id.toString(), series.id.toString())
        series.user?.let { user ->
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id,
                user.id.toString(), user.id.toString())
            linkChip(binding.chipUserLink, R.string.novel_chip_user_link,
                ShareIllust.USER_URL_Head + user.id)
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
        view.text = context.getString(labelRes, displayValue)
        view.isVisible = true
        view.setOnClick { Common.copy(context, copyValue) }
    }

    private fun linkChip(view: TextView, labelRes: Int, url: String) {
        view.text = context.getString(labelRes)
        view.isVisible = true
        view.setOnClick { Common.copy(context, url) }
    }
}

interface NovelSeriesHeaderActionReceiver
