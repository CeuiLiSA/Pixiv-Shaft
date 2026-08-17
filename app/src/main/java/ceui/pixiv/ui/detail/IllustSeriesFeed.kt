package ceui.pixiv.ui.detail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.CellMangaSeriesHeroBinding
import ceui.lisa.databinding.CellMangaSeriesItemBinding
import ceui.lisa.databinding.CellMangaSeriesProfileBinding
import ceui.lisa.databinding.CellNovelSeriesCaptionBinding
import ceui.lisa.databinding.ItemV3SectionLabelBinding
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.IllustSeriesResp
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressImageButton
import ceui.loxia.ProgressIndicator
import ceui.loxia.ProgressTextButton
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.bindCopyChip
import ceui.pixiv.ui.common.bindCopyLinkChip
import ceui.pixiv.ui.common.bindOpenLinkChip
import ceui.pixiv.ui.novel.NovelSeriesHeaderActionReceiver
import ceui.loxia.Client
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 漫画系列总话数：优先 series_work_count（/v1/illust/series 用的字段），退回 content_count。 */
fun mangaSeriesEpisodeCount(series: NovelSeriesDetail): Int =
    series.series_work_count.takeIf { it > 0 } ?: series.content_count

// ── FeedItem 模型（immutable data class；feedKey 提供身份，equals 驱动重绑）──────

data class MangaHeroFeedItem(
    val series: NovelSeriesDetail,
    val latestIllustId: Long?,
    val latestEpisodeIndex: Int?,
) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class SeriesAuthorFeedItem(val user: User) : FeedItem {
    override val feedKey: Any get() = user.id
}

data class MangaProfileFeedItem(val series: NovelSeriesDetail) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class SeriesCaptionFeedItem(val caption: String, val seriesId: Long) : FeedItem {
    override val feedKey: Any get() = seriesId
}

data class SeriesSectionLabelFeedItem(val title: String) : FeedItem {
    override val feedKey: Any get() = title
}

data class MangaEpisodeFeedItem(val illust: Illust, val episodeIndex: Int) : FeedItem {
    override val feedKey: Any get() = illust.id
}

// ── FeedSource：首页 = 头部条目 + 单话；后续页 = 仅单话（游标 = next_url）──────

class IllustSeriesFeedSource(private val seriesId: Long) : FeedSource<String> {

    // pixiv 漫画系列 /v1/illust/series 的 illusts 是「最新在前」(降序)。首页确定方向与总数，
    // 供单话话号（降序 = total - pos）与「阅读最新一话」用。source 有状态，随 load(null) 重置。
    private var descending = true
    private var episodeTotal = 0

    /**
     * 已吐出的话 id（也就是话号的位次来源），随 load(null) 重置。
     *
     * 曾经这里是个裸计数器 `emitted++`：话号 = 已吐出的话数，而这个推算隐含「吐出的每一话
     * 都会上屏」——[FeedViewModel] 并不承诺这一点。它按 identity（此处即 [MangaEpisodeFeedItem.feedKey]
     * = illust.id）去重，服务端翻页窗口漂移时必然丢条；空页追载还会连续调 load。被丢掉的话
     * 照样把计数器推进过，于是之后所有话号整体偏移，且一次翻页内无法自愈（只有刷新才归零）。
     *
     * 改由 source 自己按同一个 id 去重：吐出的必是没吐过的话，「已吐出」与「已上屏」恒等，
     * 位次可信——依赖的不变量由自己保证，而不是指望调用方。
     */
    private val emittedEpisodeIds = HashSet<Long>()

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: IllustSeriesResp = if (cursor == null) {
            Client.appApi.getIllustSeries(seriesId)
        } else {
            // .string() 阻塞读 body + gson 解析都是重活，必须切 IO（FeedSource main-safe 约定）
            withContext(Dispatchers.IO) {
                val body = Client.appApi.generalGet(cursor).string()
                Shaft.sGson.fromJson(body, IllustSeriesResp::class.java)
            }
        }

        val items = mutableListOf<FeedItem>()
        val episodes = resp.displayList

        if (cursor == null) {
            emittedEpisodeIds.clear()
            val firstEpisodeId = resp.illust_series_first_illust?.id
            descending = firstEpisodeId == null || episodes.isEmpty() ||
                    episodes.first().id != firstEpisodeId
            resp.illust_series_detail?.let { detail ->
                detail.user?.let { user -> ObjectPool.update(user) }
                episodeTotal = mangaSeriesEpisodeCount(detail)
                // 降序时列表首个即最新一话；万一判成升序，只有首页已全量(无 next_url)才能确定最后一个是最新。
                val latestIllust = if (descending) episodes.firstOrNull()
                    else episodes.lastOrNull()?.takeIf { resp.next_url == null }
                val latestIdx = if (latestIllust != null && episodeTotal > 0) episodeTotal else null
                items.add(MangaHeroFeedItem(detail, latestIllust?.id, latestIdx))
                detail.user?.let { items.add(SeriesAuthorFeedItem(it)) }
                items.add(MangaProfileFeedItem(detail))
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

        episodes.forEach { illust ->
            ObjectPool.update(illust)
            illust.user?.let { ObjectPool.update(it) }
            // 已吐过的话直接跳过（合池照做：漂移页里回来的是同一话的较新副本）
            if (!emittedEpisodeIds.add(illust.id)) return@forEach
            val pos = emittedEpisodeIds.size - 1
            val idx = if (descending && episodeTotal > 0) {
                (episodeTotal - pos).coerceAtLeast(1)
            } else {
                pos + 1
            }
            items.add(MangaEpisodeFeedItem(illust, idx))
        }

        return FeedPage(items, resp.next_url)
    }
}

// ── Renderers（复用现有 cell XML，bind 逻辑对齐旧 ViewHolder）────────────────

fun mangaHeroRenderer(): FeedRenderer<MangaHeroFeedItem, CellMangaSeriesHeroBinding> =
    feedRenderer(
        inflate = CellMangaSeriesHeroBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val series = cell.item.series
        val ctx = b.root.context

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

        val parts = mutableListOf(ctx.getString(R.string.manga_series_meta_type))
        val episodeCount = mangaSeriesEpisodeCount(series)
        if (episodeCount > 0) {
            parts.add(ctx.getString(R.string.manga_series_episode_count, episodeCount))
        }
        val density = ctx.resources.displayMetrics.density
        val palette = V3Palette.from(ctx)
        b.metaBadge.text = parts.joinToString("  ·  ")
        b.metaBadge.background = palette.pillSecondary(999f, (1 * density).toInt())
        b.metaBadge.setTextColor(palette.textAccent)

        val latestId = cell.item.latestIllustId
        val latestIdx = cell.item.latestEpisodeIndex
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

fun seriesAuthorRenderer(): FeedRenderer<SeriesAuthorFeedItem, SectionV3ArtistBinding> =
    feedRenderer(
        inflate = SectionV3ArtistBinding::inflate,
        fullSpan = true,
        recycle = { it.binding.artistAvatar.clearGlideOnRecycle() },
    ) { cell ->
        val b = cell.binding
        val user = cell.item.user
        val ctx = b.root.context
        val palette = V3Palette.from(ctx)

        b.artistName.text = user.name
        b.artistHandle.text = "@${user.account ?: ""}"

        val openUser = android.view.View.OnClickListener {
            val intent = Intent(ctx, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id.toInt())
            ctx.startActivity(intent)
        }
        b.artistCard.setOnClickListener(openUser)
        b.artistName.setOnClickListener(openUser)
        b.artistName.setOnLongClickListener { Common.copy(ctx, user.name.orEmpty()); true }
        b.artistHandle.setOnClickListener(openUser)
        b.artistHandle.setOnLongClickListener {
            Common.copy(ctx, b.artistHandle.text?.toString().orEmpty()); true
        }
        Glide.with(ctx).load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(b.artistAvatar)

        b.artistBio.isVisible = !user.comment.isNullOrBlank()
        if (b.artistBio.isVisible) b.artistBio.text = user.comment

        // 关注态：绑定时读 ObjectPool 最新值（回收/重绑不回退成快照旧值）；点按乐观切态给
        // 即时反馈，followUser/unfollowUser 走网络 + 进度圈，成功后写穿 ObjectPool。
        fun renderFollow(followed: Boolean) {
            if (followed) {
                b.followBtn.text = ctx.getString(R.string.unfollow)
                palette.applyUnfollowBtn(b.followBtn)
            } else {
                b.followBtn.text = ctx.getString(R.string.follow)
                palette.applyFollowBtn(b.followBtn)
                b.followBtn.setTextColor(Color.WHITE)
            }
        }
        renderFollow((ObjectPool.get<User>(user.id).value ?: user).is_followed == true)
        b.followBtn.setOnClick {
            val fragment = it.findFragmentOrNull<Fragment>() ?: return@setOnClick
            val nowFollowed = (ObjectPool.get<User>(user.id).value ?: user).is_followed == true
            renderFollow(!nowFollowed)
            if (nowFollowed) fragment.unfollowUser(it as ProgressTextButton, user.id.toInt())
            else fragment.followUser(it as ProgressTextButton, user.id.toInt(), PixivActions.defaultFollowRestrict())
        }
        b.followBtn.setOnLongClickListener {
            val fragment = it.findFragmentOrNull<Fragment>() ?: return@setOnLongClickListener false
            renderFollow(true)
            fragment.followUser(b.followBtn, user.id.toInt(), Params.TYPE_PRIVATE); true
        }
    }

fun mangaProfileRenderer(): FeedRenderer<MangaProfileFeedItem, CellMangaSeriesProfileBinding> =
    feedRenderer(
        inflate = CellMangaSeriesProfileBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val series = cell.item.series

        b.chipSeriesId.bindCopyChip(R.string.novel_chip_series_id, series.id.toString(), series.id.toString())
        val user = series.user
        if (user != null) {
            val name = user.name.orEmpty()
            b.chipAuthor.bindCopyChip(R.string.novel_chip_author, name, name)
            b.chipAuthorId.bindCopyChip(R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
            b.chipUserLink.bindOpenLinkChip(R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
        } else {
            b.chipAuthor.isVisible = false
            b.chipAuthorId.isVisible = false
            b.chipUserLink.isVisible = false
        }
        val episodeCount = mangaSeriesEpisodeCount(series)
        if (episodeCount > 0) {
            b.chipContentCount.bindCopyChip(R.string.manga_series_chip_content_count,
                episodeCount.toString(), episodeCount.toString())
        } else {
            b.chipContentCount.isVisible = false
        }
        if (user != null) {
            val seriesUrl = "https://www.pixiv.net/user/${user.id}/series/${series.id}"
            b.chipSeriesLink.bindCopyLinkChip(R.string.novel_chip_series_link, seriesUrl)
            b.chipOpenSeriesLink.bindOpenLinkChip(R.string.novel_chip_open_series_link, seriesUrl)
        } else {
            b.chipSeriesLink.isVisible = false
            b.chipOpenSeriesLink.isVisible = false
        }
    }

fun seriesCaptionRenderer(): FeedRenderer<SeriesCaptionFeedItem, CellNovelSeriesCaptionBinding> =
    feedRenderer(
        inflate = CellNovelSeriesCaptionBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val raw = cell.item.caption
        b.caption.isVisible = raw.isNotEmpty()
        if (raw.isNotEmpty()) {
            val normalized = raw.replace("\r\n", "\n").replace("\n", "<br/>")
            b.caption.text = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
            b.caption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(it.context, plain)
            }
        }
    }

fun seriesSectionLabelRenderer(): FeedRenderer<SeriesSectionLabelFeedItem, ItemV3SectionLabelBinding> =
    feedRenderer(
        inflate = ItemV3SectionLabelBinding::inflate,
        fullSpan = true,
    ) { cell ->
        cell.binding.label.text = cell.item.title
    }

fun mangaEpisodeRenderer(): FeedRenderer<MangaEpisodeFeedItem, CellMangaSeriesItemBinding> =
    feedRenderer(
        inflate = CellMangaSeriesItemBinding::inflate,
        recycle = { it.binding.cover.clearGlideOnRecycle() },
    ) { cell ->
        val b = cell.binding
        val illust = cell.item.illust
        val ctx = b.root.context

        val url = illust.image_urls?.let { it.square_medium ?: it.medium ?: it.large }
        Glide.with(ctx)
            .load(GlideUtil.getUrl(url))
            .placeholder(R.color.v3_surface_2)
            .error(R.color.v3_surface_2)
            .centerCrop()
            .into(b.cover)

        b.episodeIndex.text = "#${cell.item.episodeIndex}"

        val title = illust.title.orEmpty()
        b.title.isVisible = title.isNotBlank()
        b.title.text = title

        if (illust.page_count > 1) {
            b.pageCount.isVisible = true
            b.metaDot.isVisible = true
            b.pageCount.text = ctx.getString(R.string.manga_series_page_count, illust.page_count)
        } else {
            b.pageCount.isVisible = false
            b.metaDot.isVisible = false
        }
        b.publishDate.text = DateParse.getTimeAgo(ctx, illust.create_date)

        // 绑定时从 ObjectPool 读最新收藏态：卡片回收复用后不会回退成加载时的旧值。
        bindEpisodeBookmark(b, (ObjectPool.get<Illust>(illust.id).value ?: illust).is_bookmarked == true)
        b.bookmarkBtn.setOnClick { sender ->
            // 乐观切态：先本地翻心，再交给 receiver 走网络 + ObjectPool。
            val nowBookmarked = (ObjectPool.get<Illust>(illust.id).value ?: illust).is_bookmarked == true
            bindEpisodeBookmark(b, !nowBookmarked)
            sender.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(sender as ProgressIndicator, illust.id)
        }
        b.root.setOnClick { sender ->
            sender.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(illust)
        }
    }

private fun bindEpisodeBookmark(b: CellMangaSeriesItemBinding, bookmarked: Boolean) {
    b.bookmarkBtn.setImageResource(if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked)
    b.bookmarkBtn.imageTintList = if (bookmarked) null
        else ColorStateList.valueOf(b.root.context.getColor(R.color.v3_text_3))
}
