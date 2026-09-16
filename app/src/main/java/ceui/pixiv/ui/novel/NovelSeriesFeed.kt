package ceui.pixiv.ui.novel

import android.content.Intent
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellNovelSeriesHeroBinding
import ceui.lisa.databinding.CellNovelSeriesProfileBinding
import ceui.lisa.databinding.CellNovelV3Binding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.api.Client
import ceui.pixiv.utils.DateParse
import ceui.loxia.Novel
import ceui.pixiv.api.model.NovelSeriesDetail
import ceui.pixiv.api.model.NovelSeriesResp
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.widgets.ProgressImageButton
import ceui.pixiv.widgets.ProgressIndicator
import ceui.pixiv.widgets.resolveTagTranslationColor
import ceui.pixiv.ui.common.findActionReceiverOrNull
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelMultiSelectReceiver
import ceui.pixiv.ui.common.bindCopyChip
import ceui.pixiv.ui.common.bindCopyLinkChip
import ceui.pixiv.ui.common.bindOpenLinkChip
import ceui.pixiv.ui.detail.SeriesAuthorFeedItem
import ceui.pixiv.ui.detail.SeriesCaptionFeedItem
import ceui.pixiv.ui.detail.SeriesSectionLabelFeedItem
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

// ── 从旧 NovelSeriesHeaderHolder.kt 迁来的契约（同包，FQN 不变，slice-1/NovelSeries 的 import 无需改）──

interface NovelSeriesHeaderActionReceiver {
    fun onClickToggleWatchlist(progressView: ProgressImageButton)
    fun onClickReadLatestEpisode(novelId: Long)
}

// ── 多选状态（跨配置存活，与旧 NovelSeriesViewModel 的多选字段等价）───────────

class NovelSeriesSelectionViewModel : ViewModel() {
    private val _isMultiSelect = MutableLiveData(false)
    val isMultiSelect: LiveData<Boolean> = _isMultiSelect

    private val _selectedIds = MutableLiveData<Set<Long>>(emptySet())
    val selectedIds: LiveData<Set<Long>> = _selectedIds

    fun setMultiSelectMode(enabled: Boolean) {
        if (_isMultiSelect.value == enabled) return
        _isMultiSelect.value = enabled
        if (!enabled) _selectedIds.value = emptySet()
    }

    fun toggleSelection(novelId: Long) {
        val current = _selectedIds.value.orEmpty()
        _selectedIds.value = if (novelId in current) current - novelId else current + novelId
    }

    fun selectAll(ids: List<Long>) { _selectedIds.value = ids.toSet() }
    fun clearSelection() { _selectedIds.value = emptySet() }
}

// ── FeedItem 模型 ───────────────────────────────────────────────────────

data class NovelSeriesHeroFeedItem(
    val series: NovelSeriesDetail,
    val latestNovelId: Long?,
    val latestNovelChapterIndex: Int?,
) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class NovelSeriesProfileFeedItem(val series: NovelSeriesDetail) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class NovelSeriesCardFeedItem(
    val novel: Novel,
    val isMultiSelectMode: Boolean,
    val isSelected: Boolean,
) : FeedItem {
    override val feedKey: Any get() = novel.id
}

/** 多选态变化（进入/退出多选、勾选/取消勾选）的增量刷新 payload，避免重绑图片。 */
internal object NovelSeriesSelectionPayload {
    fun canBindSelectionOnly(payloads: List<Any>): Boolean =
        payloads.isNotEmpty() && payloads.all { it === this }
}

// ── FeedSource：首页 = 头部条目 + 章节卡；后续页 = 仅章节卡（游标 = next_url）──

class NovelSeriesFeedSource(private val seriesId: Long) : FeedSource<String> {
    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: NovelSeriesResp = if (cursor == null) {
            Client.appApi.getNovelSeries(seriesId)
        } else {
            // .string() 阻塞读 body + gson 解析都是重活，必须切 IO（FeedSource main-safe 约定）
            withContext(Dispatchers.IO) {
                val body = Client.appApi.generalGet(cursor).string()
                Shaft.sGson.fromJson(body, NovelSeriesResp::class.java)
            }
        }
        val items = mutableListOf<FeedItem>()
        if (cursor == null) {
            resp.novel_series_detail?.let { detail ->
                detail.user?.let { user -> ObjectPool.update(user) }
                val latestNovel = resp.novel_series_latest_novel
                val latestIdx = if (latestNovel != null && detail.content_count > 0) {
                    detail.content_count
                } else null
                items.add(NovelSeriesHeroFeedItem(detail, latestNovel?.id, latestIdx))
                detail.user?.let { items.add(SeriesAuthorFeedItem(it)) }
                items.add(NovelSeriesProfileFeedItem(detail))
                if (!detail.caption.isNullOrBlank()) {
                    items.add(SeriesCaptionFeedItem(detail.caption!!, detail.id))
                }
            }
            items.add(
                SeriesSectionLabelFeedItem(
                    Shaft.getContext().getString(R.string.novel_series_section_works)
                )
            )
        }
        resp.displayList.forEach { novel ->
            ObjectPool.update(novel)
            novel.user?.let { ObjectPool.update(it) }
            items.add(NovelSeriesCardFeedItem(novel, isMultiSelectMode = false, isSelected = false))
        }
        return FeedPage(items, resp.next_url)
    }
}

// ── Renderers ───────────────────────────────────────────────────────────

fun novelSeriesHeroRenderer(): FeedRenderer<NovelSeriesHeroFeedItem, CellNovelSeriesHeroBinding> =
    feedRenderer(
        inflate = CellNovelSeriesHeroBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val series = cell.item.series
        val ctx = b.root.context
        val fmt = NumberFormat.getInstance()

        b.title.text = series.title ?: ""
        b.title.setOnClick { Common.copy(it.context, series.title) }
        b.title.setOnLongClickListener { Common.copy(it.context, series.title); true }

        val added = series.watchlist_added == true
        b.bookmark.setImageResource(if (added) R.drawable.icon_liked else R.drawable.icon_not_liked)
        b.bookmark.imageTintList = if (added) null
            else ColorStateList.valueOf(ctx.getColor(R.color.v3_text_3))
        b.bookmark.setOnClick { v ->
            v.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()
                ?.onClickToggleWatchlist(v as ProgressImageButton)
        }

        val parts = mutableListOf(
            ctx.getString(R.string.novel_meta_type),
            ctx.getString(R.string.novel_meta_chapter_count, series.content_count),
        )
        if (series.total_character_count > 0) {
            parts.add(ctx.getString(R.string.novel_meta_word_count, fmt.format(series.total_character_count)))
        }
        val density = ctx.resources.displayMetrics.density
        val palette = V3Palette.from(ctx)
        b.metaBadge.text = parts.joinToString("  ·  ")
        b.metaBadge.background = palette.pillSecondary(999f, (1 * density).toInt())
        b.metaBadge.setTextColor(palette.textAccent)

        val latestId = cell.item.latestNovelId
        val latestIdx = cell.item.latestNovelChapterIndex
        if (latestId != null && latestIdx != null) {
            b.readLatest.isVisible = true
            b.readLatest.text = ctx.getString(R.string.read_latest_episode_with_num, latestIdx)
            b.readLatest.setOnClick { v ->
                v.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()
                    ?.onClickReadLatestEpisode(latestId)
            }
        } else {
            b.readLatest.isVisible = false
            b.readLatest.setOnClickListener(null)
        }
    }

fun novelSeriesProfileRenderer(): FeedRenderer<NovelSeriesProfileFeedItem, CellNovelSeriesProfileBinding> =
    feedRenderer(
        inflate = CellNovelSeriesProfileBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val series = cell.item.series

        b.chipSeriesId.bindCopyChip(R.string.novel_chip_series_id, series.id.toString(), series.id.toString())
        series.user?.let { user ->
            val name = user.name.orEmpty()
            b.chipAuthor.bindCopyChip(R.string.novel_chip_author, name, name)
            b.chipAuthorId.bindCopyChip(R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
            b.chipUserLink.bindOpenLinkChip(R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
        } ?: run {
            b.chipAuthor.isVisible = false
            b.chipAuthorId.isVisible = false
            b.chipUserLink.isVisible = false
        }
        if (series.content_count > 0) {
            b.chipContentCount.bindCopyChip(R.string.novel_chip_series_content_count,
                series.content_count.toString(), series.content_count.toString())
        } else {
            b.chipContentCount.isVisible = false
        }
        if (series.total_character_count > 0) {
            b.chipCharCount.bindCopyChip(R.string.novel_chip_series_char_count,
                series.total_character_count.toString(), series.total_character_count.toString())
        } else {
            b.chipCharCount.isVisible = false
        }
        val seriesUrl = "https://www.pixiv.net/novel/series/${series.id}"
        b.chipSeriesLink.bindCopyLinkChip(R.string.novel_chip_series_link, seriesUrl)
        b.chipOpenSeriesLink.bindOpenLinkChip(R.string.novel_chip_open_series_link, seriesUrl)
    }

fun novelSeriesCardRenderer(): FeedRenderer<NovelSeriesCardFeedItem, CellNovelV3Binding> =
    feedRenderer(
        inflate = CellNovelV3Binding::inflate,
        recycle = { cell ->
            cell.binding.novelCover.clearGlideOnRecycle()
            cell.binding.authorAvatar.clearGlideOnRecycle()
        },
        changePayload = { oldItem, newItem ->
            if (oldItem.novel == newItem.novel) NovelSeriesSelectionPayload else null
        },
        bindPayloads = { cell, payloads ->
            // RecyclerView 会合并多次更新；混入全量刷新标记时必须回退，不能漏绑小说内容。
            if (NovelSeriesSelectionPayload.canBindSelectionOnly(payloads)) {
                bindNovelSeriesCardSelection(cell.binding, cell.item)
                true
            } else {
                false
            }
        },
    ) { cell ->
        val b = cell.binding
        val novel = cell.item.novel
        val ctx = b.root.context
        val palette = V3Palette.from(ctx)
        val fmt = NumberFormat.getInstance()

        // cover
        val coverUrl = novel.resolvedCoverUrl()
        Glide.with(ctx).load(GlideUtil.getUrl(coverUrl))
            .placeholder(R.color.v3_surface_2).error(R.color.v3_surface_2)
            .centerCrop().into(b.novelCover)

        b.novelTitle.text = novel.title ?: ""

        val user = novel.user
        if (user != null) {
            b.authorRow.isVisible = true
            b.authorName.text = user.name ?: ""
            Glide.with(ctx).load(GlideUtil.getUrl(user.profile_image_urls?.medium))
                .placeholder(R.drawable.no_profile).error(R.drawable.no_profile).into(b.authorAvatar)
            b.authorRow.setOnClick { sender ->
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(user.id)
            }
        } else {
            b.authorRow.isVisible = false
        }

        val wordCount = novel.text_length
        if (wordCount != null && wordCount > 0) {
            b.wordCount.isVisible = true
            b.wordCount.text = ctx.getString(R.string.v3_novel_word_count, fmt.format(wordCount))
        } else {
            b.wordCount.isVisible = false
        }
        b.publishDate.text = DateParse.getTimeAgo(ctx, novel.create_date)

        b.badgeR18.isVisible = novel.is_x_restricted == true || (novel.x_restrict ?: 0) > 0
        b.badgeOriginal.isVisible = novel.is_original == true
        b.badgeAi.isVisible = novel.novel_ai_type == 2

        bindNovelCardTags(b, novel, palette)
        // 绑定时从 ObjectPool 读最新收藏态/收藏数：卡片回收复用后不会回退成加载时的旧值。
        val pooled = ObjectPool.get<Novel>(novel.id).value ?: novel
        bindNovelCardBookmark(b, pooled.is_bookmarked == true, pooled.total_bookmarks, fmt)
        b.bookmarkBtn.setOnClick { sender ->
            // 乐观切态：先本地翻心 + 收藏数 ±1，再交给 receiver 走网络 + ObjectPool。
            val now = ObjectPool.get<Novel>(novel.id).value ?: novel
            val nowBookmarked = now.is_bookmarked == true
            val nextCount = now.total_bookmarks?.let {
                (if (nowBookmarked) it - 1 else it + 1).coerceAtLeast(0)
            }
            bindNovelCardBookmark(b, !nowBookmarked, nextCount, fmt)
            sender.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(sender as ProgressIndicator, novel.id)
        }

        // multi-select indicator
        bindNovelSeriesCardSelection(b, cell.item)

        b.root.setOnClick { sender ->
            if (cell.item.isMultiSelectMode) {
                sender.findActionReceiverOrNull<NovelMultiSelectReceiver>()
                    ?.onToggleNovelSelection(novel.id)
                return@setOnClick
            }
            sender.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(novel.id)
        }
        applyCardTouchScale(b.root, 0.98f)
    }

private fun bindNovelSeriesCardSelection(b: CellNovelV3Binding, item: NovelSeriesCardFeedItem) {
    b.selectIndicator.isVisible = item.isMultiSelectMode
    if (item.isMultiSelectMode) {
        if (item.isSelected) {
            b.selectIndicator.setImageResource(R.drawable.ic_check_circle_black_24dp)
            b.selectIndicator.clearColorFilter()
        } else {
            b.selectIndicator.setImageResource(R.drawable.ic_checkbox_off)
            b.selectIndicator.setColorFilter(b.root.context.getColor(R.color.v3_text_3))
        }
    }
}

private fun bindNovelCardBookmark(
    b: CellNovelV3Binding,
    bookmarked: Boolean,
    totalBookmarks: Int?,
    fmt: NumberFormat,
) {
    b.bookmarkBtn.setImageResource(if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked)
    b.bookmarkBtn.imageTintList = if (bookmarked) null
        else ColorStateList.valueOf(b.root.context.getColor(R.color.v3_text_3))
    // 收藏数字段缺失时整条隐藏，不显示 0 —— 0 会被读成「没人收藏」而不是「不知道」。
    b.bookmarkCount.isVisible = totalBookmarks != null
    if (totalBookmarks != null) b.bookmarkCount.text = fmt.format(totalBookmarks)
}

private fun bindNovelCardTags(b: CellNovelV3Binding, novel: Novel, palette: V3Palette) {
    val tags = novel.tags
    val ctx = b.root.context
    // 与主力小说卡同一套设置（#982/#1047/#1089）：「小说列表显示标签」关闭时整体隐藏；
    // 译文默认关闭、可单独开启；「小说列表标签折叠」开启时超 6 个折叠成「+N」。
    if (tags.isNullOrEmpty() || !Shaft.sSettings.isShowNovelCardTags) {
        b.tagsSection.isVisible = false
        return
    }
    b.tagsSection.isVisible = true
    b.tagsFlow.removeAllViews()
    val density = ctx.resources.displayMetrics.density
    val tagBg = palette.tagLockedBg(999f * density).constantState
    val maxTags = if (Shaft.sSettings.isCollapseNovelCardTags) 6 else -1
    val showTranslations = Shaft.sSettings.isShowNovelCardTagTranslations
    val translationColor = resolveTagTranslationColor(palette)
    val visibleTags = if (maxTags > 0) tags.take(maxTags) else tags
    visibleTags.forEach { tag ->
        val tv = TextView(ctx)
        val translationSuffix = tag.translated_name
            ?.takeIf { showTranslations && it.isNotBlank() }
            ?.let { "  $it" }
            .orEmpty()
        val fullText = "# ${tag.name ?: ""}$translationSuffix"
        tv.text = SpannableString(fullText).apply {
            if (translationSuffix.isNotEmpty()) {
                setSpan(
                    ForegroundColorSpan(translationColor),
                    fullText.length - translationSuffix.length,
                    fullText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        tv.textSize = 11f
        tv.setTextColor(palette.textTag)
        tv.background = tagBg?.newDrawable()?.mutate()
        tv.setPaddingRelative(10.ppppx, 5.ppppx, 10.ppppx, 5.ppppx)
        val lp = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(0, 0, 6.ppppx, 6.ppppx)
        lp.flexShrink = 0f
        tv.layoutParams = lp
        tv.setOnClickListener {
            val intent = Intent(ctx, SearchActivity::class.java)
                .putExtra(Params.KEY_WORD, tag.name)
                .putExtra(Params.INDEX, 1)
            ctx.startActivity(intent)
        }
        applyCardTouchScale(tv, 0.94f)
        b.tagsFlow.addView(tv)
    }
    if (maxTags > 0 && tags.size > maxTags) {
        val overflow = TextView(ctx)
        overflow.text = "+${tags.size - maxTags}"
        overflow.textSize = 11f
        overflow.setTextColor(palette.textSecondary)
        overflow.background = tagBg?.newDrawable()?.mutate()
        overflow.setPaddingRelative(10.ppppx, 5.ppppx, 10.ppppx, 5.ppppx)
        val overflowLp = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        overflowLp.setMargins(0, 0, 6.ppppx, 6.ppppx)
        overflowLp.flexShrink = 0f
        overflow.layoutParams = overflowLp
        b.tagsFlow.addView(overflow)
    }
}

private fun applyCardTouchScale(view: View, scale: Float) {
    view.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        false
    }
}
