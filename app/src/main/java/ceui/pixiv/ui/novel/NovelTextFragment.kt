package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.loxia.Novel
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.widgets.ProgressIndicator
import ceui.loxia.Series
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.IllustIdActionReceiver
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.openIllustsInViewer
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.toggleIllustBookmark
import ceui.pixiv.ui.common.toggleNovelBookmark
import ceui.pixiv.ui.detail.SectionLoader
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
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 小说详情页（feeds 框架版）。固定卡：标题+系列 → 作者 → 作品档案 → 功能按钮 →
 * 标签 → 简介（超长折叠 #1005）；其后是两个懒加载区块（作者往期作品 / 相关小说，
 * issue #1005 对齐插画详情），区块卡直接复用 [NovelFeedFragment] 的主力小说卡——
 * 收藏同步 / 屏蔽遮罩 / 长按菜单随基类一起生效；底部「开始阅读」浮动按钮。
 * 数据住在 [feedViewModel]（[NovelTextFeedSource]，单页无分页）；各卡渲染器
 * （[NovelTextFeed.kt]）观察 ObjectPool 拿实时小说数据（收藏 / 元信息随点赞即时刷新）。
 *
 * 入口：[TemplateActivity] 路由「小说详情」+ NOVEL_ID(Long)。跳转一律走 Intent
 * （TemplateActivity 无 NavHost，pushFragment 会崩）。
 */
class NovelTextFragment :
    NovelFeedFragment(R.layout.fragment_v3_feed_bottombar),
    NovelActionsReceiver,
    NovelActionReceiver,
    NovelSectionReceiver,
    NovelSeriesActionReceiver,
    IllustCardActionReceiver,
    IllustIdActionReceiver,
    UserActionReceiver,
    ExportFormatCallback {

    private val novelId: Long by lazy { arguments?.getLong(Params.NOVEL_ID, 0L) ?: 0L }
    private var viewHistoryInserted = false

    /** 简介折叠态（展开与否归 Fragment，滚走再滚回不重置，对齐插画详情）。 */
    private val captionCollapse = NovelCaptionCollapse()

    /** 懒加载区块触发器（复用插画侧实现，三层失败恢复见其 KDoc）。 */
    private var sectionLoader: SectionLoader<NovelDetailSection>? = null

    override val feedViewModel by feedViewModels {
        val id = novelId
        NovelTextFeedSource(id)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        novelHeaderRenderer(viewLifecycleOwner),
        seriesAuthorRenderer(),
        novelProfileRenderer(viewLifecycleOwner),
        novelActionsRenderer(viewLifecycleOwner),
        novelTagsRenderer(viewLifecycleOwner),
        novelCaptionRenderer(viewLifecycleOwner, captionCollapse, ::scrollCaptionBackIntoView),
        novelSectionHeaderRenderer(),
        // 区块卡不展示标签（issue #1005 报告人的建议：避免区块过长喧宾夺主）
        novelCardRenderer(showTags = false),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(LinearItemDecorationNoLRTB(18.ppppx))
    }

    /** 详情页首屏是标题/简介卡，不是小说卡列表：不用基类的小说卡骨架，维持转圈圈。 */
    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView? =
        null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val listView = feedBinding.feedListView

        // 懒加载区块触发器。下拉刷新会整代换新条目（区块头回到未加载态），此时必须换一个
        // 触发器重置去重集——旧集里记着「已成功」，新一代的区块头会永远停在转圈上。
        sectionLoader = SectionLoader(viewLifecycleOwner) { it.load(novelId, feedViewModel) }
        var lastGeneration = feedViewModel.uiState.value.refreshGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state ->
                    if (state.refreshGeneration != lastGeneration) {
                        lastGeneration = state.refreshGeneration
                        sectionLoader =
                            SectionLoader(viewLifecycleOwner) { it.load(novelId, feedViewModel) }
                    }
                }
            }
        }

        // 底部「开始阅读」浮动按钮（对齐旧 bottom_covered 里的 ItemBigReadButton）。
        val bottomBar = view.findViewById<FrameLayout>(R.id.bottom_bar)
        val readButton = ItemBigReadButtonBinding.inflate(layoutInflater)
        val palette = V3Palette.from(requireContext())
        readButton.btnRead.background = palette.pillPrimary(28f * density)
        readButton.btnRead.setOnClick {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
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
                            ceui.loxia.Novel::class.java,
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

    override fun onDestroyView() {
        sectionLoader = null
        super.onDestroyView()
    }

    // ─── 懒加载区块（issue #1005）───────────────────────────────────────────

    /** 区块头 attach/bind 且数据仍空时调用：转给 [SectionLoader]（去重 + 单飞 + 视图作用域）。 */
    override fun onNovelSectionVisible(section: NovelDetailSection) {
        sectionLoader?.onVisible(section)
    }

    /**
     * 联网后补拉加载失败的区块。区块的触发信号只有 holder 的 attach/bind，用户停在
     * 那一屏不动（区块正转圈时最常见），不补这一下就再也没有重试时机（对齐插画详情）。
     */
    override fun onNetworkRestored() {
        super.onNetworkRestored()
        sectionLoader?.retryFailed()
    }

    /** 收起超长简介后把简介块拉回视口（理由见插画侧 scrollDescBackIntoView 的 KDoc）。 */
    private fun scrollCaptionBackIntoView(itemView: View) {
        if (itemView.top >= 0) return
        val rv = feedBinding.feedListView
        val pos = rv.getChildAdapterPosition(itemView)
        if (pos == RecyclerView.NO_POSITION) return
        rv.scrollToPosition(pos)
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
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
            putExtra(Params.NOVEL_ID, novelId.toInt())
        }
        startActivity(intent)
    }

    override fun onClickDownloadNovel(sender: View, novelId: Long) {
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) executeExport(format, allowAutoEpub = true) else showExportSheet()
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

    private fun executeExport(format: ExportFormat, allowAutoEpub: Boolean = false) {
        val appContext = requireContext().applicationContext
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
                // 仅默认 TXT 快路径允许静默切 EPUB；手动在“每次询问”里选 TXT 不切。
                val actualFormat = if (allowAutoEpub && NovelExportManager.shouldAutoEpubForDefaultTxt(format, tokens)) {
                    ExportFormat.Epub
                } else {
                    format
                }
                Toaster.show(getString(R.string.msg_export_start, getString(actualFormat.displayNameResId)))
                NovelExportManager.export(
                    context = appContext,
                    format = actualFormat,
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
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
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
