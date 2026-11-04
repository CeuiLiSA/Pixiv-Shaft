package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.databinding.ViewV3ReadPillBinding
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.loxia.Novel
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.download.toast.DownloadToastKind
import ceui.pixiv.download.toast.DownloadToasts
import ceui.pixiv.widgets.ProgressIndicator
import ceui.loxia.Series
import ceui.pixiv.feeds.FeedCell
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
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.ReaderProgressStore
import ceui.pixiv.ui.novel.reader.export.EpubImageRetryPolicy
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.RateAppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 小说详情页（feeds 框架版，V3「小说详情」，见 docs/v3-design-philosophy.md）。
 * 头卡是 hero（模糊封面铺进状态栏 + 居中封面 / 标题 / 作者 / 元信息 + 阅读 / 收藏 / 下载）、
 * 统计条与系列行；其下是用细线分开的区块：简介（超长折叠 #1005）→ 标签 → 信息 → 作者 +
 * 其他作品 → 相关作品。后两块懒加载（issue #1005 对齐插画详情），区块卡直接复用
 * [NovelFeedFragment] 的主力小说卡——收藏同步 / 屏蔽遮罩 / 长按菜单随基类一起生效。
 * 头卡的阅读按钮滚出屏幕后，底部浮出同文案的「阅读」胶囊（同一屏不会有两个阅读入口）。
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

    /** 阅读进度百分比（null = 没读过）：头卡主按钮与悬浮胶囊共用，onResume 时从阅读器存档刷新。 */
    private val readingPercent = MutableLiveData<Int?>(null)

    /** 状态栏高度：列表顶不留 padding，头卡 hero 自己把内容往下让这么多。 */
    private var statusBarInset = 0

    private var readPill: TextView? = null
    private var readPillShown = false

    /** 往下滑过 → 胶囊让开（#1177「滑动隐藏」，别挡长简介）；往上滑或回到顶再放回。 */
    private var readPillHiddenByScroll = false

    /** hero 滚走后盖住状态栏的页面底色条，透明度随滚动渐显。 */
    private var statusScrim: View? = null

    override val feedViewModel by feedViewModels {
        val id = novelId
        NovelTextFeedSource(id)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        val palette = V3Palette.from(requireContext())
        return listOf(
            novelHeaderRenderer(viewLifecycleOwner, novelGlide, readingPercent) { statusBarInset },
            novelCaptionRenderer(viewLifecycleOwner, captionCollapse, ::scrollCaptionBackIntoView),
            novelTagsRenderer(viewLifecycleOwner),
            novelInfoRenderer(viewLifecycleOwner),
            novelAuthorRenderer(viewLifecycleOwner, novelGlide, palette),
            novelSectionHeaderRenderer(),
            // 区块卡不展示标签（issue #1005 报告人的建议：避免区块过长喧宾夺主）
            novelCardRenderer(showTags = false),
        )
    }

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(
            NovelDetailDecoration(requireContext(), V3Palette.from(requireContext()).cardHairline)
        )
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

        val ctx = requireContext()
        val palette = V3Palette.from(ctx)

        // 宽度变了（分屏 / 自由窗口改宽度不一定重建）要按新宽度重算宽屏留白；首次布局（旧宽 0）不算
        listView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            if (oldWidth != 0 && right - left != oldWidth) (v as RecyclerView).invalidateItemDecorations()
        }

        // hero 铺进状态栏下面；滚走之后内容会从状态栏底下穿过，用一条页面底色渐显盖住
        val root = view as ViewGroup
        val scrim = View(ctx).apply {
            setBackgroundColor(ctx.color(R.color.v3_bg))
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(scrim, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        statusScrim = scrim

        // 底部悬浮「阅读」胶囊（#1177）：半透明、和插画详情的悬浮胶囊同尺寸、下滑让开上滑放回。
        // 只在头卡主按钮滚出屏幕后出现，文案与主按钮一致（阅读正文 / 继续阅读 · N%）。
        val bottomBar = view.findViewById<FrameLayout>(R.id.bottom_bar)
        val pill = ViewV3ReadPillBinding.inflate(layoutInflater, bottomBar, false).root
        pill.background = palette.floatingPillBg(999f * density)
        pill.setTextColor(palette.floatingPillContent)
        val bookIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_baseline_menu_book_24)
            ?.mutate()
            ?.apply {
                val size = (20 * density).toInt()
                setBounds(0, 0, size, size)
                setTint(palette.floatingPillContent)
            }
        pill.setCompoundDrawablesRelative(null, null, bookIcon, null)
        pill.setOnClick { onClickReadNovel(it, novelId) }
        pill.visibility = View.INVISIBLE
        pill.alpha = 0f
        pill.translationY = READ_PILL_HIDDEN_OFFSET_DP * density
        readPillShown = false
        readPillHiddenByScroll = false
        bottomBar.addView(pill)
        readPill = pill
        readingPercent.observe(viewLifecycleOwner) { pill.text = novelReadLabel(ctx, it) }

        // 布局变动（含数据首次落地、简介展开收起）时 RV 也会回调 onScrolled(0, 0)，这里接得住。
        // 滑动方向的阈值沿用旧胶囊（±8px 以内的抖动不算换向）；到顶（含内容短到不够滚）一律清掉
        // 「下滑让开」，否则收起超长简介后列表可能再也上滑不了，胶囊会永远藏着。
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                when {
                    !recyclerView.canScrollVertically(-1) -> readPillHiddenByScroll = false
                    dy > READ_PILL_SCROLL_SLOP_PX -> readPillHiddenByScroll = true
                    dy < -READ_PILL_SCROLL_SLOP_PX -> readPillHiddenByScroll = false
                }
                updateFloatingChrome()
            }
        })

        // Edge-to-edge：列表顶不留 padding（hero 自己让状态栏），底部让出胶囊高度；
        // 胶囊距底 = 导航栏 inset + 24dp（与插画详情胶囊一致）；下拉刷新的圈从状态栏之下出来。
        var appliedRefreshTop = -1
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(top = 0, bottom = bars.bottom + (96 * density).toInt())
            pill.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + (24 * density).toInt()
            }
            scrim.updateLayoutParams { height = bars.top }
            if (bars.top != statusBarInset) {
                statusBarInset = bars.top
                headerBinding()?.applyStatusBarInset(bars.top)
            }
            // 挡住「没变就别重设」：setProgressViewOffset 内部会 requestLayout（同 UserActivityV3）
            if (bars.top != appliedRefreshTop) {
                appliedRefreshTop = bars.top
                feedBinding.feedRefreshLayout.setProgressViewOffset(false, bars.top, bars.top + ctx.dp(64))
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)

        // 浏览历史：小说数据到位后记一次。gson 往返（整个 Novel 序列化再反序列化）+ 入库都不便宜，
        // 切后台跑——这条是纯旁路副作用，没人等它的结果，不该占进页那一帧的主线程。
        ObjectPool.get<Novel>(novelId).observe(viewLifecycleOwner) { novel ->
            // 滚动模式的进度按字数算，字数要等详情落池才有
            refreshReadingProgress()
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

    override fun onResume() {
        super.onResume()
        // 从阅读器返回时进度已经变了
        refreshReadingProgress()
    }

    override fun onDestroyView() {
        sectionLoader = null
        readPill = null
        statusScrim = null
        super.onDestroyView()
    }

    // ─── Hero / 悬浮胶囊 ────────────────────────────────────────────────────

    private fun refreshReadingProgress() {
        val percent = novelReadingPercent(
            charIndex = ReaderProgressStore.loadCharIndex(novelId),
            pageIndex = ReaderProgressStore.loadLastPageIndex(novelId),
            totalPages = ReaderProgressStore.loadTotalPages(novelId),
            textLength = ObjectPool.get<Novel>(novelId).value?.text_length,
        )
        if (readingPercent.value != percent) readingPercent.value = percent
    }

    /**
     * 屏幕上的头卡（滚走被回收了就是 null）。按已挂上的子 View 找，不用
     * findViewHolderForAdapterPosition(0)：区块懒加载插卡时 adapter 有待处理的更新，
     * 那条路会短暂返回 null，胶囊就会在主按钮还在屏幕上时误浮出来。
     */
    private fun headerBinding(): CellNovelHeaderBinding? {
        val list = view?.let { feedBinding.feedListView } ?: return null
        for (i in 0 until list.childCount) {
            val cell = list.getChildViewHolder(list.getChildAt(i)) as? FeedCell<*, *> ?: continue
            (cell.binding as? CellNovelHeaderBinding)?.let { return it }
        }
        return null
    }

    /**
     * 跟着滚动走的两件事：头卡主按钮滚到状态栏之下、且不在往下滑 → 浮出阅读胶囊；hero 往上滚 →
     * 状态栏底色渐显。头卡不在屏幕上（已滚远被回收）时两者都是「完全出现」；列表还空着（首屏加载 /
     * 出错）不出胶囊。
     */
    private fun updateFloatingChrome() {
        val list = view?.let { feedBinding.feedListView } ?: return
        val header = headerBinding()
        val readOffscreen: Boolean
        val heroScrolled: Float
        if (header != null) {
            val top = header.root.top
            readOffscreen = top + header.ctaRow.bottom < statusBarInset
            heroScrolled = (-top).toFloat()
        } else {
            readOffscreen = (list.adapter?.itemCount ?: 0) > 0
            heroScrolled = Float.MAX_VALUE
        }
        setReadPillShown(readOffscreen && !readPillHiddenByScroll)
        statusScrim?.alpha = (heroScrolled / (STATUS_SCRIM_FADE_DP * resources.displayMetrics.density))
            .coerceIn(0f, 1f)
    }

    private fun setReadPillShown(show: Boolean) {
        val pill = readPill ?: return
        if (show == readPillShown) return
        readPillShown = show
        pill.animate().cancel()
        if (show) pill.visibility = View.VISIBLE
        pill.animate()
            .translationY(if (show) 0f else READ_PILL_HIDDEN_OFFSET_DP * resources.displayMetrics.density)
            .alpha(if (show) 1f else 0f)
            .setDuration(READ_PILL_ANIMATION_DURATION_MS)
            .withEndAction { if (!readPillShown) pill.visibility = View.INVISIBLE }
            .start()
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

    override fun onClickReadNovel(sender: View, novelId: Long) {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
            putExtra(Params.NOVEL_ID, novelId)
        })
    }

    override fun onClickNovelCover(sender: View, novelId: Long) {
        ObjectPool.get<Novel>(novelId).value?.let(::openCoverImage)
    }

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
                DownloadToasts.show(
                    DownloadToastKind.NOVEL_SAVE,
                    getString(R.string.msg_export_start, getString(actualFormat.displayNameResId)),
                )
                NovelExportManager.export(
                    context = appContext,
                    format = actualFormat,
                    novel = novel,
                    webNovel = web,
                    tokens = tokens,
                    retryPolicy = EpubImageRetryPolicy.Foreground(activity),
                )
            }.getOrElse { ExportResult.Failure(it.message ?: "导出失败", it) }
            when (result) {
                is ExportResult.Success -> DownloadToasts.show(
                    DownloadToastKind.NOVEL_SAVE,
                    appContext.getString(R.string.msg_export_success, result.displayPath),
                )
                is ExportResult.Failure -> DownloadToasts.show(
                    DownloadToastKind.NOVEL_SAVE,
                    appContext.getString(R.string.msg_export_fail, result.message),
                )
                is ExportResult.Skipped -> DownloadToasts.show(
                    DownloadToastKind.NOVEL_SAVE,
                    appContext.getString(R.string.msg_export_skipped, result.displayPath),
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
        /** 与插画详情悬浮胶囊的收起/放回动画同时长。 */
        private const val READ_PILL_ANIMATION_DURATION_MS = 200L

        /** 单次 onScrolled 位移超过它才算换了滑动方向（同旧胶囊）。 */
        private const val READ_PILL_SCROLL_SLOP_PX = 8

        /** 收起时胶囊往下沉的距离（够它整个沉出屏幕底边）。 */
        private const val READ_PILL_HIDDEN_OFFSET_DP = 120f

        /** hero 上滚这么多之后状态栏底色完全不透明（≈ 分享那一行滚进状态栏的行程）。 */
        private const val STATUS_SCRIM_FADE_DP = 48f

        fun newInstance(novelId: Long): NovelTextFragment = NovelTextFragment().apply {
            arguments = Bundle().apply { putLong(Params.NOVEL_ID, novelId) }
        }
    }
}
