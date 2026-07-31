package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.http.Retro
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ProgressImageButton
import ceui.loxia.ProgressIndicator
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.IllustIdActionReceiver
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.common.openIllustsInViewer
import ceui.pixiv.ui.common.toggleIllustBookmark
import ceui.pixiv.ui.novel.NovelSeriesHeaderActionReceiver
import ceui.pixiv.utils.ppppx
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 漫画系列 V3 详情页（feeds 框架版）。整页镜像小说系列详情页的观感：全屏 hero（标题 +
 * 收藏 + 阅读最新一话）、作者卡、作品档案、简介、「作品列表」标题 + 标题优先的单话列表。
 *
 * 数据全部住在 [feedViewModel]（[IllustSeriesFeedSource]）；hero / 作者 / 档案 / 简介 /
 * 单话都是 [FeedItem]，各自的 [FeedRenderer] 在 [IllustSeriesFeed.kt]。列表项点击一律走
 * VActivity + PageData（TemplateActivity 无 NavHost，pushFragment 会崩）。
 *
 * 入口两条：① TemplateActivity "漫画系列详情"；② NavHost navigation_illust_series。
 * 都靠 arguments 里的 "series_id"(Long)。
 */
class IllustSeriesFragment :
    FeedFragment(R.layout.fragment_v3_feed_list),
    NovelSeriesHeaderActionReceiver,
    IllustCardActionReceiver,
    IllustIdActionReceiver {

    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }

    override val feedViewModel by feedViewModels {
        val id = seriesId
        IllustSeriesFeedSource(id)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        mangaHeroRenderer(),
        seriesAuthorRenderer(),
        mangaProfileRenderer(),
        seriesCaptionRenderer(),
        seriesSectionLabelRenderer(),
        mangaEpisodeRenderer(),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(LinearItemDecorationNoLRTB(18.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val listView = feedBinding.feedListView
        // Edge-to-edge：TemplateActivity 画到状态栏/刘海下面，列表首个 holder 清掉 systemBars.top
        // 再留一点呼吸位；底部让出导航栏 inset + 一点空隙。
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(
                top = bars.top + (12 * density).toInt(),
                bottom = bars.bottom + (24 * density).toInt(),
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun loadedIllusts(): List<Illust> =
        feedViewModel.uiState.value.items.filterIsInstance<MangaEpisodeFeedItem>().map { it.illust }

    // ── 单话/最新话点击：打开整条系列的查看器并定位到这一话 ─────────────

    override fun onClickIllustCard(illust: Illust) = onClickIllust(illust.id)

    override fun visitIllustById(illustId: Long) = onClickIllust(illustId)

    override fun onClickIllust(illustId: Long) {
        val illusts = loadedIllusts()
        val pos = illusts.indexOfFirst { it.id == illustId }
        if (illusts.isEmpty() || pos < 0) {
            // 兜底：列表还没加载到这一话（理论上不会），单独拉取只放它一个。
            viewLifecycleOwner.lifecycleScope.launch {
                val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                    .getOrNull() ?: return@launch
                openIllustsInViewer(listOf(illust), 0)
            }
            return
        }
        openIllustsInViewer(illusts, pos)
    }

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) =
        toggleIllustBookmark(sender, illustId)

    // ── NovelSeriesHeaderActionReceiver（hero 卡片复用小说那套接口）─────────

    override fun onClickToggleWatchlist(progressView: ProgressImageButton) {
        val hero = feedViewModel.uiState.value.items
            .filterIsInstance<MangaHeroFeedItem>().firstOrNull() ?: return
        progressView.showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = hero.series
                val nowAdded = detail.watchlist_added == true
                val seriesIdInt = detail.id.toInt()
                val obs = if (nowAdded) {
                    Retro.getAppApi().postWatchlistMangaDelete(seriesIdInt)
                } else {
                    Retro.getAppApi().postWatchlistMangaAdd(seriesIdInt)
                }
                obs.awaitFirstValue()
                // 本地翻转 watchlist_added 并重发 hero 条目触发重绑收藏 icon。
                feedViewModel.updateItems<MangaHeroFeedItem> {
                    it.copy(series = it.series.copy(watchlist_added = !nowAdded))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) Toaster.show(getString(R.string.task_status_error))
            } finally {
                if (isAdded) progressView.hideProgress()
            }
        }
    }

    override fun onClickReadLatestEpisode(novelId: Long) {
        // 参数名沿用接口的 novelId，这里其实是最新一话的 illustId。跳转与列表点击一致。
        onClickIllust(novelId)
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): IllustSeriesFragment = IllustSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
