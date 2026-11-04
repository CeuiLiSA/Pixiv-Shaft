package ceui.pixiv.ui.novel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.databinding.CellNovelInfoBinding
import ceui.lisa.databinding.CellNovelSeriesHeroBinding
import ceui.lisa.databinding.CellNovelSeriesTocHeaderBinding
import ceui.lisa.helper.WebLinkHelper
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.ShareIllust
import ceui.loxia.Novel
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.NovelSeriesDetail
import ceui.pixiv.api.model.NovelSeriesResp
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.findActionReceiverOrNull
import ceui.pixiv.ui.common.realCoverUrl
import ceui.pixiv.ui.detail.applyDetailPanelExpanded
import ceui.pixiv.ui.novel.reader.ReaderProgressStore
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.DateParse
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.ProgressImageButton
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

// ── 从旧 NovelSeriesHeaderHolder.kt 迁来的契约（同包，FQN 不变；漫画系列页也实现它）──

interface NovelSeriesHeaderActionReceiver {
    fun onClickToggleWatchlist(progressView: ProgressImageButton)
    fun onClickReadLatestEpisode(novelId: Long)
}

/** 小说系列页头上只有小说系列才有的操作（漫画系列页不实现）。 */
interface NovelSeriesHeroActionReceiver {
    /** 主按钮：继续阅读 / 从第 1 篇开始。 */
    fun onClickSeriesRead(sender: View)
    fun onClickSeriesDownload(sender: View)
    fun onClickShareSeries(sender: View)
    fun onClickSeriesCover(sender: View)
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
    /** 系列封面（缩略图 URL，见 [pickSeriesCoverUrl]）；作者谁都没设封面时为 null。 */
    val coverUrl: String?,
    val firstNovelId: Long?,
    val latestNovelId: Long?,
    val latestTitle: String?,
    val latestCreateDate: String?,
    /** 最新一篇是第几篇（= content_count）。 */
    val latestIndex: Int?,
) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class NovelSeriesCaptionFeedItem(val seriesId: Long, val caption: String) : FeedItem {
    override val feedKey: Any get() = seriesId
}

/** 「信息」区块（取代旧「作品档案」chip 墙）；[startedAt] 是第 1 篇的发布时间。 */
data class NovelSeriesInfoFeedItem(val series: NovelSeriesDetail, val startedAt: String?) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class NovelSeriesTocHeaderFeedItem(val seriesId: Long, val count: Int) : FeedItem {
    override val feedKey: Any get() = seriesId
}

/** 单篇的阅读进度，读阅读器的本地存档（MMKV，内存映射，逐行读不贵）。 */
internal fun novelSeriesReadPercent(novel: Novel): Int? = novelReadingPercent(
    charIndex = ReaderProgressStore.loadCharIndex(novel.id),
    pageIndex = ReaderProgressStore.loadLastPageIndex(novel.id),
    totalPages = ReaderProgressStore.loadTotalPages(novel.id),
    textLength = novel.text_length,
)

internal fun novelSeriesUrl(seriesId: Long): String = "https://www.pixiv.net/novel/series/$seriesId"

// ── FeedSource：首页 = 页头条目 + 章节；后续页 = 仅章节（游标 = next_url）──────

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
            // 顺序同 V3「小说系列」：页头（Hero 卡 + 操作 + 最新一篇）→ 简介 → 作者 → 信息 → 目录。
            // 目录分页往下追加，必须放最后。
            val detail = resp.novel_series_detail
            if (detail != null) {
                detail.user?.let { ObjectPool.update(it) }
                val first = resp.novel_series_first_novel
                val latest = resp.novel_series_latest_novel
                val coverUrl = pickSeriesCoverUrl(
                    detail.id,
                    (listOf(first, latest) + resp.displayList).map { it?.realCoverUrl },
                )
                items.add(
                    NovelSeriesHeroFeedItem(
                        series = detail,
                        coverUrl = coverUrl,
                        firstNovelId = first?.id ?: resp.displayList.firstOrNull()?.id,
                        latestNovelId = latest?.id,
                        latestTitle = latest?.title,
                        latestCreateDate = latest?.create_date,
                        latestIndex = detail.content_count.takeIf { latest != null && it > 0 },
                    )
                )
                detail.caption?.takeIf { it.isNotBlank() }?.let {
                    items.add(NovelSeriesCaptionFeedItem(detail.id, it))
                }
                detail.user?.let { items.add(NovelAuthorFeedItem(it)) }
                items.add(NovelSeriesInfoFeedItem(detail, first?.create_date))
            }
            items.add(NovelSeriesTocHeaderFeedItem(seriesId, detail?.content_count ?: 0))
        }
        // 目录用全 app 通用的小说卡（recy_novel）。不过内容过滤：系列是用户点进来的，滤掉会让目录缺篇、
        // 篇号对不上；屏蔽 / AI 打码由卡片自己按现行设置遮盖（同其它小说列表）。
        resp.displayList.forEach { novel ->
            ObjectPool.update(novel)
            novel.user?.let { ObjectPool.update(it) }
            items.add(NovelFeedItem(novel))
        }
        return FeedPage(items, resp.next_url)
    }
}

// ── 页头 ────────────────────────────────────────────────────────────────

/** 页头的状态栏让位：列表顶不留 padding，页头从这条 spacer 之下开始。 */
internal fun CellNovelSeriesHeroBinding.applyStatusBarInset(top: Int) {
    if (statusSpacer.layoutParams.height != top) statusSpacer.updateLayoutParams { height = top }
}

/** 主按钮文案：没读过「从第 1 篇开始」，读过「继续阅读 · 第 N 篇」（不知道第几篇时只写「继续阅读」）。 */
internal fun novelSeriesReadLabel(context: Context, target: SeriesReadTarget?): String = when {
    target == null -> context.getString(R.string.novel_series_read_first)
    target.index != null -> context.getString(R.string.novel_series_continue_at, target.index)
    else -> context.getString(R.string.novel_series_continue)
}

/**
 * 小说系列页头：Hero 卡（叠放封面 + 小标 / 标题 / 作者 / 连载状态 + 三格统计）→ 操作行 → 最新一篇。
 * 和小说详情同一套设计语言，但构图不同（见 cell_novel_series_hero 的注释）。颜色全部从
 * [V3Palette] 派生，换主题色 / 日夜即跟随。
 */
fun novelSeriesHeroRenderer(
    lifecycleOwner: LifecycleOwner,
    glide: RequestManager,
    readTarget: LiveData<SeriesReadTarget?>,
    statusBarInset: () -> Int,
): FeedRenderer<NovelSeriesHeroFeedItem, CellNovelSeriesHeroBinding> {
    val targetSlots = HashMap<FeedCell<*, *>, CellObserverSlot<SeriesReadTarget?>>()
    val numbers = NumberFormat.getInstance()
    return feedRenderer(
        inflate = CellNovelSeriesHeroBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            styleNovelSeriesHero(b)
            // 监听只挂一次，点击那一刻经 cell.itemOrNull 取当下条目
            b.share.setOnClick { v ->
                v.findActionReceiverOrNull<NovelSeriesHeroActionReceiver>()?.onClickShareSeries(v)
            }
            b.cover.setOnClick { v ->
                if (cell.itemOrNull?.coverUrl == null) return@setOnClick
                v.findActionReceiverOrNull<NovelSeriesHeroActionReceiver>()?.onClickSeriesCover(v)
            }
            val copyTitle = { Common.copy(b.root.context, cell.itemOrNull?.series?.title) }
            b.title.setOnClick { copyTitle() }
            b.title.setOnLongClickListener { copyTitle(); true }
            b.authorRow.setOnClick { v ->
                val userId = cell.itemOrNull?.series?.user?.id?.takeIf { it > 0 } ?: return@setOnClick
                v.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(userId)
            }
            b.read.setOnClick { v ->
                v.findActionReceiverOrNull<NovelSeriesHeroActionReceiver>()?.onClickSeriesRead(v)
            }
            // sender 传圆钮里的 ProgressImageButton：请求在飞时原地转圈
            b.watchlist.setOnClick {
                b.watchlistIcon.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()
                    ?.onClickToggleWatchlist(b.watchlistIcon)
            }
            b.download.setOnClick { v ->
                v.findActionReceiverOrNull<NovelSeriesHeroActionReceiver>()?.onClickSeriesDownload(v)
            }
            b.latestRow.setOnClick { v ->
                val id = cell.itemOrNull?.latestNovelId ?: return@setOnClick
                v.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()?.onClickReadLatestEpisode(id)
            }
        },
        recycle = { cell ->
            targetSlots[cell]?.detach()
            val b = cell.binding
            glide.clear(b.cover)
            glide.clear(b.authorAvatar)
            b.cover.setTag(R.id.novel_header_cover_key, null)
            b.authorAvatar.setTag(R.id.novel_header_avatar_key, null)
        },
        // 从 RV 缓存直接 re-attach 的页头不会重绑；状态栏高度在它离屏期间变了也要跟上
        attach = { cell -> cell.binding.applyStatusBarInset(statusBarInset()) },
    ) { cell ->
        val b = cell.binding
        b.applyStatusBarInset(statusBarInset())
        bindNovelSeriesHero(b, cell.item, glide, numbers)
        targetSlots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(readTarget) { target -> b.readLabel.text = novelSeriesReadLabel(b.root.context, target) }
    }
}

/** 页头里不随数据变的部分：卡片底、书页、细线、图标着色。只在 create 跑一次。 */
private fun styleNovelSeriesHero(b: CellNovelSeriesHeroBinding) {
    val ctx = b.root.context
    val p = V3Palette.from(ctx)
    val text2 = ctx.color(R.color.v3_text_2)
    // 涟漪 mask 每个 View 一份：同一个 Drawable 实例只能挂一个 callback
    fun round() = shape(999f, Color.WHITE)

    // 状态圆点对齐首行中线（字号随字体缩放，create 时按画笔度量算一次）
    val fm = b.status.paint.fontMetricsInt
    val firstBaseline = if (b.status.includeFontPadding) -fm.top else -fm.ascent
    val firstLineCenter = firstBaseline + (fm.ascent + fm.descent) / 2
    b.statusDot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = (firstLineCenter - ctx.dp(6) / 2).coerceAtLeast(0)
    }

    // Hero 卡：30dp 圆角，cardFill 上从起始角斜向晕开一层主题色 15%，一圈 hairline（不铺模糊封面）
    b.heroCard.background = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ColorUtils.compositeColors(p.alpha15, p.cardFill), p.cardFill),
    ).apply {
        cornerRadius = ctx.dpF(30f)
        setStroke(ctx.hairlinePx(), p.cardHairline)
    }

    // 叠放的书：后两张书页是主题色压在卡底上的不透明色（50% / 28%），8dp 圆角 + hairline
    val pageRadius = ctx.dpF(8f)
    b.stackPage1.background = shape(
        pageRadius, ColorUtils.compositeColors(V3Palette.withAlpha(p.primary, 0.50f), p.cardFill),
        p.cardHairline, ctx.hairlinePx(),
    )
    b.stackPage2.background = shape(
        pageRadius, ColorUtils.compositeColors(V3Palette.withAlpha(p.primary, 0.28f), p.cardFill),
        p.cardHairline, ctx.hairlinePx(),
    )
    // 封面：8dp 圆角裁切 + 细描边（浅色封面在浅底上也有边）+ 阴影
    b.cover.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, pageRadius)
        }
    }
    b.cover.clipToOutline = true
    b.cover.foreground = shape(
        pageRadius,
        Color.TRANSPARENT,
        V3Palette.withAlpha(ctx.color(R.color.v3_text_1), if (p.isDark) 0.14f else 0.10f),
        ctx.hairlinePx(),
    )

    b.eyebrow.setTextColor(p.textAccent)
    ViewCompat.setAccessibilityHeading(b.title, true)
    b.authorChevron.setImageDrawable(chevron(ctx, text2))
    b.statsLine.setBackgroundColor(p.cardHairline)

    // 待操作图标：哑光主题色（浅色）/ 白（深色），不用主题正色也不用黑灰
    b.share.background = ctx.ripple(null, round())
    b.share.imageTintList = ColorStateList.valueOf(p.floatingPillContent)

    // 主操作：实色主题胶囊（全页唯一最强的元素），字和书本图标用 onPrimary
    b.read.background = ctx.ripple(p.pillPrimary(999f), round())
    b.readLabel.setTextColor(p.onPrimary)
    AppCompatResources.getDrawable(ctx, R.drawable.ic_baseline_menu_book_24)?.mutate()?.let {
        it.setTint(p.onPrimary)
        it.setBounds(0, 0, ctx.dp(20), ctx.dp(20))
        b.readLabel.setCompoundDrawablesRelative(it, null, null, null)
    }
    b.read.pressScale()
    for (circle in listOf(b.watchlist, b.download)) {
        circle.background = ctx.ripple(p.pillSecondary(999f, ctx.hairlinePx()), round())
        circle.pressScale()
    }
    b.downloadIcon.imageTintList = ColorStateList.valueOf(p.floatingPillContent)
    TooltipCompat.setTooltipText(b.download, ctx.getString(R.string.series_download_action))

    // 最新一篇：40dp、17/17/17/7 的类别图标容器，主题色 alpha20 底
    val big = ctx.dpF(17f)
    val small = ctx.dpF(7f)
    b.latestIcon.background = GradientDrawable().apply {
        cornerRadii = floatArrayOf(big, big, big, big, big, big, small, small)
        setColor(p.alpha20)
    }
    b.latestIconImage.imageTintList = ColorStateList.valueOf(p.textSecondary)
    b.latestChevron.setImageDrawable(chevron(ctx, text2))

    // 标记：主题色 alpha10 底 + 正文色（同小说详情）
    val badgeRadius = ctx.dpF(6f)
    for (badge in listOf(b.badgeOriginal, b.badgeAi)) {
        badge.background = shape(badgeRadius, p.alpha10)
        badge.setTextColor(ctx.color(R.color.v3_text_1))
    }
}

private fun bindNovelSeriesHero(
    b: CellNovelSeriesHeroBinding,
    item: NovelSeriesHeroFeedItem,
    glide: RequestManager,
    numbers: NumberFormat,
) {
    val ctx = b.root.context
    val p = V3Palette.from(ctx)
    val series = item.series

    loadSeriesCover(b, item.coverUrl, glide, p)
    // 叠在封面后面的书页随篇数露出：1 篇不叠，2 篇一张，3 篇及以上两张
    b.stackPage1.isVisible = series.content_count >= 2
    b.stackPage2.isVisible = series.content_count >= 3

    b.title.text = series.title

    val user = series.user
    b.authorRow.isVisible = user != null
    if (user != null) {
        b.authorName.text = user.name
        val avatar = user.profile_image_urls?.medium
        if (b.authorAvatar.getTag(R.id.novel_header_avatar_key) != avatar) {
            b.authorAvatar.setTag(R.id.novel_header_avatar_key, avatar)
            glide.load(GlideUtil.getHead(user)).error(R.drawable.no_profile).into(b.authorAvatar)
        }
    }

    // 连载状态：语义色圆点 + 「连载中 · 2026-11-03 更新」
    val concluded = series.is_concluded == true
    b.statusDot.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (concluded) ctx.color(R.color.v3_green) else p.textAccent)
    }
    val status = ctx.getString(if (concluded) R.string.novel_series_concluded else R.string.novel_series_ongoing)
    val updated = item.latestCreateDate?.take(10)?.takeIf { it.isNotEmpty() }
    b.status.text = if (updated == null) status
    else status + " · " + ctx.getString(R.string.novel_series_updated_on, updated)

    b.badgeOriginal.isVisible = series.is_original == true
    // 系列的 AI 口径同单篇：novel_ai_type == 2 才算 AI 作品
    b.badgeAi.isVisible = series.novel_ai_type == 2
    b.badges.isVisible = b.badgeOriginal.isVisible || b.badgeAi.isVisible

    b.statChapters.text = numbers.format(series.content_count)
    b.statCharacters.text =
        series.total_character_count.takeIf { it > 0 }?.let(numbers::format) ?: STAT_UNKNOWN
    b.statReadingTime.text = when (val d = seriesReadingDuration(series.total_character_count)) {
        is SeriesReadingDuration.Minutes ->
            withSmallUnits(ctx.resources.getQuantityString(R.plurals.novel_reading_minutes, d.value, d.value))
        is SeriesReadingDuration.Hours ->
            withSmallUnits(ctx.resources.getQuantityString(R.plurals.novel_reading_hours, d.value, d.value))
        null -> STAT_UNKNOWN
    }

    bindWatchlist(b, series.watchlist_added == true, p)

    // 最新一篇：两篇以上才有意义（只有一篇时它就是目录里那一行）
    val latestId = item.latestNovelId
    val latestIndex = item.latestIndex
    b.latestRow.isVisible = latestId != null && latestIndex != null && series.content_count >= 2
    if (b.latestRow.isVisible && latestIndex != null) {
        val label = ctx.getString(R.string.novel_series_latest_chapter, latestIndex)
        val ago = item.latestCreateDate?.let { DateParse.getTimeAgo(ctx, it) }
        b.latestLabel.text = if (ago.isNullOrEmpty()) label else "$label · $ago"
        b.latestTitle.text = item.latestTitle
    }
}

private fun bindWatchlist(b: CellNovelSeriesHeroBinding, added: Boolean, p: V3Palette) {
    val ctx = b.root.context
    // 同小说详情的收藏钮：未追是哑光主题色的空心，已追是原色红心（阅读器「加入追更列表」也是这颗心）
    b.watchlistIcon.setImageResource(if (added) R.drawable.icon_liked else R.drawable.icon_not_liked)
    b.watchlistIcon.imageTintList = if (added) null else ColorStateList.valueOf(p.floatingPillContent)
    val description = ctx.getString(
        if (added) R.string.reader_menu_watchlist_remove else R.string.add_to_watchlist,
    )
    b.watchlist.contentDescription = description
    TooltipCompat.setTooltipText(b.watchlist, description)
}

/**
 * 封面优先拿母图（[novelCoverMasterUrl]，240 宽缩略图在 96dp 的封面上发糊），缩略图当 thumbnail 先出、
 * 母图失败时兜底。没有封面时留一块主题色浅底（和书页同色系），不画灰占位图。
 */
private fun loadSeriesCover(b: CellNovelSeriesHeroBinding, coverUrl: String?, glide: RequestManager, p: V3Palette) {
    if (b.cover.getTag(R.id.novel_header_cover_key) == (coverUrl ?: NO_COVER)) return
    b.cover.setTag(R.id.novel_header_cover_key, coverUrl ?: NO_COVER)
    val placeholder = ColorUtils.compositeColors(p.alpha20, p.cardFill).toDrawable()
    if (coverUrl == null) {
        glide.clear(b.cover)
        b.cover.setImageDrawable(placeholder)
        return
    }
    val master = novelCoverMasterUrl(coverUrl)
    if (master == null) {
        glide.load(GlideUtil.getUrl(coverUrl)).placeholder(placeholder).error(placeholder).into(b.cover)
        return
    }
    glide.load(GlideUtil.getUrl(master))
        .thumbnail(glide.load(GlideUtil.getUrl(coverUrl)))
        .error(glide.load(GlideUtil.getUrl(coverUrl)))
        .placeholder(placeholder)
        .into(b.cover)
}

private const val NO_COVER = "<none>"
private const val STAT_UNKNOWN = "—"

/** 统计数字 18sp，夹在数字前后的单位文字（「约」「小时」「min」）缩到 12sp：数字大于数字单位。 */
private fun withSmallUnits(text: String): CharSequence {
    val out = SpannableString(text)
    fun isNumberPart(i: Int): Boolean {
        val c = text[i]
        if (c.isDigit()) return true
        // 千分位 / 小数点只有夹在两个数字之间才算数字的一部分
        return c in ",.  " && i > 0 && i < text.length - 1 &&
            text[i - 1].isDigit() && text[i + 1].isDigit()
    }
    var i = 0
    while (i < text.length) {
        if (isNumberPart(i)) {
            i++
            continue
        }
        val start = i
        while (i < text.length && !isNumberPart(i)) i++
        out.setSpan(RelativeSizeSpan(12f / 18f), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return out
}

// ── 简介 / 信息 / 目录标题 ───────────────────────────────────────────────

/** 系列简介：同小说详情的简介格（链接、超长折叠、点按复制），数据就是系列详情里那段 caption。 */
fun novelSeriesCaptionRenderer(
    collapse: NovelCaptionCollapse,
    onCollapsed: (View) -> Unit,
): FeedRenderer<NovelSeriesCaptionFeedItem, CellNovelCaptionBinding> =
    feedRenderer(
        inflate = CellNovelCaptionBinding::inflate,
        fullSpan = true,
        create = { cell -> setupNovelCaptionCell(cell.binding, collapse, onCollapsed) },
        recycle = { cell -> recycleNovelCaptionCell(cell.binding) },
    ) { cell ->
        renderNovelCaption(cell.binding, cell.item.caption, collapse)
    }

/**
 * 「信息」区块：复用小说详情的信息表（cell_novel_info），三行标签换成「开始连载 / 系列 ID / 系列链接」。
 * 折叠态归本 renderer（= 本页面实例），初始值随设置（#1044）。
 */
fun novelSeriesInfoRenderer(): FeedRenderer<NovelSeriesInfoFeedItem, CellNovelInfoBinding> {
    var expanded = !Shaft.sSettings.isDetailPanelCollapsedByDefault
    return feedRenderer(
        inflate = CellNovelInfoBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            val ctx = b.root.context
            styleNovelInfoCell(b)
            b.labelPublished.setText(R.string.novel_series_info_started)
            b.labelNovelId.setText(R.string.novel_info_series_id)
            b.labelNovelLink.setText(R.string.novel_chip_series_link)
            b.infoHeader.setOnClickListener {
                expanded = !expanded
                applyDetailPanelExpanded(b.infoRows, b.infoArrow, expanded, animate = true)
            }
            fun item(): NovelSeriesInfoFeedItem? = cell.itemOrNull
            b.rowPublished.setOnClick { Common.copy(ctx, item()?.startedAt ?: return@setOnClick) }
            b.rowNovelId.setOnClick { Common.copy(ctx, item()?.series?.id?.toString() ?: return@setOnClick) }
            b.rowUserId.setOnClick { Common.copy(ctx, item()?.series?.user?.id?.toString() ?: return@setOnClick) }
            b.novelLinkCopy.setOnClick { Common.copy(ctx, novelSeriesUrl(item()?.series?.id ?: return@setOnClick)) }
            b.novelLinkOpen.setOnClick {
                WebLinkHelper.openInCustomTab(ctx, novelSeriesUrl(item()?.series?.id ?: return@setOnClick))
            }
            b.userLinkCopy.setOnClick {
                Common.copy(ctx, ShareIllust.USER_URL_Head + (item()?.series?.user?.id ?: return@setOnClick))
            }
            b.userLinkOpen.setOnClick {
                WebLinkHelper.openInCustomTab(
                    ctx, ShareIllust.USER_URL_Head + (item()?.series?.user?.id ?: return@setOnClick),
                )
            }
        },
    ) { cell ->
        val b = cell.binding
        val item = cell.item
        applyDetailPanelExpanded(b.infoRows, b.infoArrow, expanded, animate = false)
        val started = item.startedAt?.replace('T', ' ')?.take(16)
        b.rowPublished.isVisible = !started.isNullOrEmpty()
        b.line1.isVisible = b.rowPublished.isVisible
        b.valuePublished.text = started
        b.valueNovelId.text = item.series.id.toString()
        val userId = item.series.user?.id?.takeIf { it > 0 }
        b.rowUserId.isVisible = userId != null
        b.line3.isVisible = userId != null
        b.line4.isVisible = userId != null
        b.rowUserLink.isVisible = userId != null
        b.valueUserId.text = userId?.toString()
    }
}

fun novelSeriesTocHeaderRenderer(): FeedRenderer<NovelSeriesTocHeaderFeedItem, CellNovelSeriesTocHeaderBinding> =
    feedRenderer(
        inflate = CellNovelSeriesTocHeaderBinding::inflate,
        fullSpan = true,
        create = { cell -> ViewCompat.setAccessibilityHeading(cell.binding.tocTitle, true) },
    ) { cell ->
        val b = cell.binding
        val count = cell.item.count
        b.tocCount.isVisible = count > 0
        if (count > 0) {
            b.tocCount.text = b.root.resources.getQuantityString(R.plurals.novel_series_toc_count, count, count)
        }
    }
