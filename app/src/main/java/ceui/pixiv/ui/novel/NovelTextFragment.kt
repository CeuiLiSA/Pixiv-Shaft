package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.Series
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.IllustIdActionReceiver
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.openIllustsInViewer
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.toggleIllustBookmark
import ceui.pixiv.ui.common.toggleNovelBookmark
import ceui.pixiv.ui.detail.seriesAuthorRenderer
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.RateAppManager
import com.hjq.toast.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * 小说详情页（feeds 框架版）。固定 6 张卡：标题+系列 → 作者 → 作品档案 → 功能按钮 →
 * 标签 → 简介；底部「开始阅读」浮动按钮。数据全部住在 [feedViewModel]
 * （[NovelTextFeedSource]，单页无分页）；各卡渲染器（[NovelTextFeed.kt]）观察 ObjectPool
 * 拿实时小说数据（收藏 / 元信息随点赞即时刷新）。
 *
 * 入口：[TemplateActivity] 路由「小说详情」+ NOVEL_ID(Long)。跳转一律走 Intent
 * （TemplateActivity 无 NavHost，pushFragment 会崩）。
 */
class NovelTextFragment :
    FeedFragment(R.layout.fragment_v3_feed_bottombar),
    NovelActionsReceiver,
    NovelActionReceiver,
    NovelSeriesActionReceiver,
    IllustCardActionReceiver,
    IllustIdActionReceiver,
    UserActionReceiver,
    ExportFormatCallback {

    private val novelId: Long by lazy { arguments?.getLong(Params.NOVEL_ID, 0L) ?: 0L }
    private var viewHistoryInserted = false

    override val feedViewModel by feedViewModels {
        val id = novelId
        NovelTextFeedSource(id)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        novelHeaderRenderer(viewLifecycleOwner),
        seriesAuthorRenderer(),
        novelProfileRenderer(viewLifecycleOwner),
        novelActionsRenderer(),
        novelTagsRenderer(viewLifecycleOwner),
        novelCaptionRenderer(viewLifecycleOwner),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(LinearItemDecorationNoLRTB(18.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val listView = feedBinding.feedListView

        // 底部「开始阅读」浮动按钮（对齐旧 bottom_covered 里的 ItemBigReadButton）。
        val bottomBar = view.findViewById<FrameLayout>(R.id.bottom_bar)
        val readButton = ItemBigReadButtonBinding.inflate(layoutInflater)
        val palette = V3Palette.from(requireContext())
        readButton.btnRead.background = palette.pillPrimary(28f * density)
        readButton.btnRead.setOnClick {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                putExtra(Params.NOVEL_ID, novelId)
            }
            ctx.startActivity(intent)
        }
        bottomBar.addView(readButton.root)

        // Edge-to-edge safe area：ItemBigReadButton 是 300dp 渐变遮罩容器，必须铺到屏幕最底
        // （内容在其后柔和淡出），只在容器内底 padding 里叠加导航栏 inset 把按钮抬起——
        // 千万别给容器加 bottomMargin，那会把整块渐变抬离屏幕底变成「漂浮的渐变」。
        // 列表首行清状态栏、底部让出按钮遮罩高度。
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(top = bars.top, bottom = bars.bottom + (96 * density).toInt())
            readButton.root.updatePadding(bottom = (20 * density).toInt() + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)

        // 浏览历史：小说数据到位后记一次。gson 往返（整个 Novel 序列化再反序列化）+ 入库都不便宜，
        // 切后台跑——这条是纯旁路副作用，没人等它的结果，不该占进页那一帧的主线程。
        ObjectPool.get<Novel>(novelId).observe(viewLifecycleOwner) { novel ->
            if (novel != null && !viewHistoryInserted) {
                viewHistoryInserted = true
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val bean = Shaft.sGson.fromJson(
                            Shaft.sGson.toJson(novel),
                            ceui.lisa.models.NovelBean::class.java,
                        )
                        ceui.lisa.utils.PixivOperate.insertNovelViewHistory(bean)
                    }.onFailure { Timber.w(it, "小说浏览历史写入失败(忽略)") }
                }
            }
        }

        // Fire-and-forget：后台预热 V3 reader 数据（拉 HTML → 解析 → tokenize → 落缓存），
        // 用户点「开始阅读」秒开。缓存命中直接跳过。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (NovelTextCache.get(novelId) != null) return@runCatching
                val html = Client.appApi.getNovelText(novelId).string()
                val web = ceui.lisa.fragments.WebNovelParser.parsePixivObject(html)?.novel
                    ?: return@runCatching
                val tokens = ContentParser.tokenize(web)
                NovelTextCache.put(novelId, NovelTextCache.Entry(web, tokens))
            }
        }
    }

    // ─── NovelActionsReceiver ──────────────────────────────────────────────

    override fun onClickShareNovel(sender: View, novelId: Long) {
        val novel = ObjectPool.get<Novel>(novelId).value
        if (novel != null) {
            shareNovel(novel)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val fresh = runCatching { Client.appApi.getNovel(novelId).novel }
                    .getOrNull()?.also { ObjectPool.update(it) }
                if (fresh != null) shareNovel(fresh)
            }
        }
    }

    override fun onClickNovelComments(sender: View, novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
            putExtra(Params.NOVEL_ID, novelId.toInt())
        }
        startActivity(intent)
    }

    override fun onClickDownloadNovel(sender: View, novelId: Long) {
        val defaultFormat = Shaft.sSettings.defaultNovelExportFormat
        val format = ExportFormat.entries.firstOrNull { it.name == defaultFormat }
        if (format != null) executeExport(format) else showExportSheet()
    }

    override fun onLongClickDownloadNovel(sender: View, novelId: Long) {
        showExportSheet()
    }

    private fun showExportSheet() {
        ExportSheet().show(childFragmentManager, ExportSheet.TAG)
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        executeExport(format)
    }

    private fun executeExport(format: ExportFormat) {
        val appContext = requireContext().applicationContext
        Toaster.show(getString(R.string.msg_export_start, getString(format.displayNameResId)))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val novel = ObjectPool.get<Novel>(novelId).value
                    ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
                val cached = NovelTextCache.get(novelId)
                val web = cached?.webNovel ?: withContext(Dispatchers.IO) {
                    val html = Client.appApi.getNovelText(novelId).string()
                    ceui.lisa.fragments.WebNovelParser.parsePixivObject(html)?.novel
                } ?: error("invalid web novel")
                val tokens = cached?.tokens ?: withContext(Dispatchers.IO) {
                    ContentParser.tokenize(web)
                }
                if (cached == null) {
                    NovelTextCache.put(novelId, NovelTextCache.Entry(web, tokens))
                }
                NovelExportManager.export(
                    context = appContext,
                    format = format,
                    novel = novel,
                    webNovel = web,
                    tokens = tokens,
                )
            }.getOrElse { ExportResult.Failure(it.message ?: "导出失败", it) }
            when (result) {
                is ExportResult.Success -> Toaster.show(
                    appContext.getString(R.string.msg_export_success, result.displayPath)
                )
                is ExportResult.Failure -> Toaster.show(
                    appContext.getString(R.string.msg_export_fail, result.message)
                )
            }
        }
    }

    // ─── 导航 receivers（经典 Intent）──────────────────────────────────────

    override fun onClickUser(id: Long) = openUserActivity(id)

    override fun onClickNovel(novelId: Long) = openNovelDetail(novelId)

    override fun visitNovelById(novelId: Long) = onClickNovel(novelId)

    override fun onClickNovelSeries(sender: View, series: Series) {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列")
            putExtra(NovelSeriesFragment.ARG_SERIES_ID, series.id)
        })
    }

    override fun onClickIllustCard(illust: Illust) = onClickIllust(illust.id)

    override fun visitIllustById(illustId: Long) = onClickIllust(illustId)

    override fun onClickIllust(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            openIllustsInViewer(listOf(illust), 0)
        }
    }

    // ─── bookmark receivers ────────────────────────────────────────────────

    override fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long) =
        toggleNovelBookmark(sender, novelId)

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) =
        toggleIllustBookmark(sender, illustId)

    companion object {
        fun newInstance(novelId: Long): NovelTextFragment = NovelTextFragment().apply {
            arguments = Bundle().apply { putLong(Params.NOVEL_ID, novelId) }
        }
    }
}
