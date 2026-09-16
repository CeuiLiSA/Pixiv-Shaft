package ceui.pixiv.ui.novel.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.database.NovelBookmarkEntity
import ceui.lisa.databinding.FragmentNovelReaderV3Binding
import ceui.loxia.Novel
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.pixiv.api.Client
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.cache.SeriesCache
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import java.util.UUID
import ceui.pixiv.ui.detail.V3MenuBuilder
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.model.HighlightColor
import ceui.pixiv.ui.novel.reader.model.HighlightSpan
import ceui.pixiv.ui.novel.reader.model.SearchHit
import ceui.pixiv.ui.novel.reader.model.TextSelection
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.GlideImageBitmapSource
import ceui.pixiv.ui.novel.reader.render.HighlightRange
import ceui.pixiv.ui.novel.reader.model.ReadingDirection
import ceui.pixiv.ui.novel.reader.render.NovelReaderView
import ceui.pixiv.ui.novel.reader.render.NovelScrollReaderView
import ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView
import ceui.pixiv.ui.novel.reader.render.PageOverlays
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import ceui.pixiv.ui.novel.reader.ui.AnnotationSheetCallback
import ceui.pixiv.ui.novel.reader.ui.AnnotationsSheet
import ceui.pixiv.ui.novel.reader.ui.BookmarkSheetCallback
import ceui.pixiv.ui.novel.reader.ui.BookmarksSheet
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import ceui.pixiv.ui.novel.reader.ui.ChapterListSheet
import ceui.pixiv.ui.novel.reader.ui.ChapterSheetCallback
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.novel.reader.ui.NoteEditorCallback
import ceui.pixiv.ui.novel.reader.ui.NoteEditorDialog
import ceui.pixiv.ui.novel.reader.ui.ReaderBottomBar
import ceui.pixiv.ui.novel.reader.ui.ReaderChrome
import ceui.pixiv.ui.novel.reader.ui.ReaderSearchOverlay
import ceui.pixiv.ui.novel.reader.ui.ReaderSettingsPanel
import ceui.pixiv.ui.novel.reader.ui.ReaderTopBar
import ceui.pixiv.ui.novel.reader.ui.SearchHitSheetCallback
import ceui.pixiv.ui.novel.reader.ui.SearchHitsSheet
import ceui.pixiv.ui.novel.reader.ui.SeriesListSheet
import ceui.pixiv.ui.novel.reader.ui.SeriesNavCallback
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import ceui.pixiv.services.appServices
import ceui.pixiv.ui.navigation.TemplateRoute

class NovelReaderV3Fragment : Fragment(R.layout.fragment_novel_reader_v3),
    SeriesNavCallback, ExportFormatCallback, BookmarkSheetCallback, AnnotationSheetCallback,
    ChapterSheetCallback, SearchHitSheetCallback, NoteEditorCallback {

    private val binding by viewBinding(FragmentNovelReaderV3Binding::bind)
    private val viewModel: NovelReaderV3ViewModel by viewModels {
        NovelReaderV3ViewModel.factory(
            resolveNovelId(),
            requireContext().appServices().discoveryPool,
            arguments?.getString(ARG_LOCAL_URI),
            arguments?.getString(ARG_LOCAL_TITLE),
        )
    }

    /** 本地 txt 源标记（来自 [newInstanceLocal]）。用于隐藏 pixiv 专属按钮 / 菜单项。 */
    private val isLocalSource: Boolean
        get() = !arguments?.getString(ARG_LOCAL_URI).isNullOrEmpty()

    private var readerView: NovelReaderView? = null
    private var scrollReaderView: NovelScrollReaderView? = null
    private var imageSource: GlideImageBitmapSource? = null
    // Held so ensureScrollReaderView's onScrollProgressChanged callback can
    // drive the bottom seekbar without having to be inlined into onViewCreated.
    private var bottomBar: ReaderBottomBar? = null
    private var chrome: ReaderChrome? = null
    /** 常驻阅读进度当前读数（形如 "43%"），随翻页/滚动刷新。 */
    private var progressOverlayText: String = ""

    private var searchRegex: Boolean = false
    private var activeSelection: TextSelection? = null
    private var annotationSpans: List<HighlightSpan> = emptyList()

    private var lastPushedSnapshot: ReaderSettings.Snapshot? = null
    private var lastPushedWidth: Int = 0
    private var lastPushedHeight: Int = 0
    private var lastPushedThemeIsDark: Boolean = false
    private var lastPushedTopInset: Int = 0
    private var lastPushedBottomInset: Int = 0
    private var topInsetPx: Int = 0
    private var bottomInsetPx: Int = 0

    /**
     * 当前小说所属 series 的追更状态缓存。reader 每次只加载一篇小说，所以
     * loadState→Loaded 触发时若 seriesId 跟 [watchlistSeriesId] 不同就重新拉
     * 一次 NovelSeriesDetail.watchlist_added；同一 seriesId 不重复请求。
     */
    private var watchlistSeriesId: Long? = null
    private var watchlistAdded: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = NovelReaderView(requireContext()).also {
            it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            binding.readerStage.addView(it)
        }
        readerView = rv

        imageSource = GlideImageBitmapSource(requireContext()) { _ ->
            rv.invalidate()
        }.also { rv.setBitmapSource(it) }

        val tb = ReaderTopBar(binding.readerTopBar)
        val bb = ReaderBottomBar(binding.readerBottomBar)
        bottomBar = bb
        val ch = ReaderChrome(tb, bb)
        chrome = ch
        ch.onVisibilityChanged = { refreshProgressOverlay() }
        val so = ReaderSearchOverlay(binding.readerSearchOverlay)

        wireTopBar(tb)
        tb.setPixivActionsVisible(!isLocalSource)
        wireBottomBar(rv, bb, ch, so)
        wireReaderView(rv, ch)
        wireSearchOverlay(so, ch)
        wireTextSelection(rv, ch)
        wireSystemBarInsets()
        wireBackPress(ch, so)

        observeReaderState(rv, tb, bb, so, ch)

        rv.setTouchLocked(ReaderSettings.touchLocked)
        rv.setTapZoneReversed(ReaderSettings.tapZoneReversed)
        binding.root.keepScreenOn = ReaderSettings.keepScreenOn

        // 立即应用阅读器主题背景色，避免加载中显示白底
        val theme = ReaderTheme.findPresetById(ReaderSettings.themeId) ?: ReaderTheme.KRAFT
        binding.root.setBackgroundColor(theme.backgroundColor)
        applyLoadingTint(theme)

        if (ReaderSettings.readingDirection == ReadingDirection.Vertical) {
            rv.visibility = View.GONE
            ensureScrollReaderView(ch).visibility = View.VISIBLE
            // Data binds later: viewModel.load() → Loaded observer → rebindScrollViewIfActive()
        }
        viewModel.load()
    }

    // ---- Wiring -------------------------------------------------------------

    /**
     * 返回手势:搜索层 > 顶底栏 > 退出。callback 只在前两者之一显示时 enabled:常开会让系统
     * 放弃预测式返回动画;都收起后返回就是退出阅读器,必须交还给系统才有跟手的退出预览。
     * 搜索层关闭时会顺手 chrome.show()(见 [closeSearch]),所以刷新 enabled 只看两者的显隐通知。
     */
    private fun wireBackPress(chrome: ReaderChrome, so: ReaderSearchOverlay) {
        val cb = object : androidx.activity.OnBackPressedCallback(so.isShown() || chrome.isShown) {
            override fun handleOnBackPressed() {
                // 优先级 1：正文搜索 overlay 打开 → 返回手势先退出搜索（与 onClose 行为一致）。
                if (so.isShown()) {
                    closeSearch(so, chrome)
                    return
                }
                // 优先级 2：阅读器 chrome（顶/底栏）显示中 → 返回手势先收起 chrome。
                if (chrome.isShown) {
                    chrome.hide()
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        val refresh = { cb.isEnabled = so.isShown() || chrome.isShown }
        val chromeListener = chrome.onVisibilityChanged
        chrome.onVisibilityChanged = { shown -> chromeListener?.invoke(shown); refresh() }
        so.onShownChanged = { refresh() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
    }

    private fun wireSystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val extraTop = (8 * resources.displayMetrics.density).toInt()
            binding.readerTopBar.root.updatePadding(top = bars.top)
            binding.readerSearchOverlay.root.updatePadding(top = bars.top + extraTop)
            binding.readerBottomBar.root.updatePadding(bottom = bars.bottom)
            if (bars.top != topInsetPx || bars.bottom != bottomInsetPx) {
                topInsetPx = bars.top
                bottomInsetPx = bars.bottom
                pushStyleAndGeometryIfReady()
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun wireReaderView(rv: NovelReaderView, chrome: ReaderChrome) {
        rv.onTapCenter = {
            if (activeSelection != null) clearSelection() else chrome.toggle()
        }
        rv.onImageTap = { image -> openImageElement(image) }
        rv.onJumpTap = { jump -> handleJumpTap(jump.target) }
        rv.onEdgeHit = { /* edge feedback: vibrate later */ }
        rv.onPageChanged = { index ->
            viewModel.onPageChanged(index)
            if (activeSelection != null) clearSelection()
        }
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pushStyleAndGeometryIfReady()
        }
    }

    private fun wireTopBar(tb: ReaderTopBar) {
        tb.onBackClick = { activity?.finish() }
        tb.onAnnotationsClick = { showAnnotationsSheet() }
        tb.onLikeClick = { togglePixivBookmark() }
        tb.onLikeLongClick = { openTagBookmarkForCurrentNovel() }
        // 丝带图标 = pixiv 原版书签（しおり/marker），不是收藏 —— issue #935。
        tb.onMarkerClick = { togglePixivMarker() }
        tb.onMoreClick = { showTopMoreMenu() }
    }

    private fun wireBottomBar(rv: NovelReaderView, bb: ReaderBottomBar, chrome: ReaderChrome, so: ReaderSearchOverlay) {
        bb.onPrevChapter = { jumpChapter(forward = false) }
        bb.onNextChapter = { jumpChapter(forward = true) }
        bb.onChaptersClick = { showChapterDrawer() }
        bb.onSeriesClick = { showSeriesSheet() }
        bb.onSettingsClick = {
            ReaderSettingsPanel().show(childFragmentManager, ReaderSettingsPanel.TAG)
        }
        bb.onThemeToggleClick = {
            val isDark = currentThemeIsDark()
            ReaderSettings.themeId = if (isDark) ReaderTheme.KRAFT.id else ReaderTheme.NIGHT.id
            bb.setDarkMode(!isDark)
        }
        bb.onSearchClick = {
            chrome.hide()
            so.setShown(true)
            // Let the overlay measure, then tell the scroll reader how much top
            // area is covered so search-hit centering avoids the hidden zone.
            so.view.post { scrollReaderView?.topInset = so.view.height }
        }
        bb.onSeekCommit = { pageIndex -> rv.goToPage(pageIndex, animate = false) }
        bb.onScrollSeekCommit = { fraction ->
            scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.scrollToFraction(fraction)
        }
    }

    private fun wireSearchOverlay(so: ReaderSearchOverlay, chrome: ReaderChrome) {
        so.onQueryChanged = { runSearch(it) }
        so.onNext = { jumpToHit(1) }
        so.onPrev = { jumpToHit(-1) }
        so.onRegexToggle = { regex ->
            searchRegex = regex
            runSearch(so.currentQuery())
        }
        so.onClose = { closeSearch(so, chrome) }
        so.onListClick = { showSearchHitsSheet() }
    }

    /** 关闭正文搜索的唯一路径（X 按钮与返回手势共用）。关掉后恢复 chrome：
     *  开搜索时 chrome 被收起，若不恢复，下一次返回就直接退出阅读器，
     *  习惯性连滑两次会误退 —— issue #1004。 */
    private fun closeSearch(so: ReaderSearchOverlay, chrome: ReaderChrome) {
        so.setShown(false)
        so.clear()
        viewModel.clearSearch()
        scrollReaderView?.topInset = 0
        chrome.show()
    }

    private fun wireTextSelection(rv: NovelReaderView, chrome: ReaderChrome) {
        val idCopy = 1; val idShare = 2; val idSearchPixiv = 3
        val idSearchWeb = 4; val idTranslate = 5
        val idHighlightParent = 9; val idNote = 20

        rv.setTextBlockSelectionHandlers(
            onStart = { _, absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text.toString())
                chrome.hide()
            },
            onChange = { _, absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text.toString())
            },
            onEnd = { activeSelection = null },
            menuEntries = listOf(
                ReaderTextBlockView.MenuEntry(idCopy, getString(R.string.action_copy)),
                ReaderTextBlockView.MenuEntry(ReaderTextBlockView.ID_SELECT_ALL, getString(R.string.action_select_all)),
                ReaderTextBlockView.MenuEntry(idHighlightParent, getString(R.string.action_highlight)),
                ReaderTextBlockView.MenuEntry(idNote, getString(R.string.action_note)),
                ReaderTextBlockView.MenuEntry(idTranslate, getString(R.string.action_translate)),
                ReaderTextBlockView.MenuEntry(idSearchPixiv, getString(R.string.action_search_pixiv)),
                ReaderTextBlockView.MenuEntry(idSearchWeb, getString(R.string.action_search_web)),
                ReaderTextBlockView.MenuEntry(idShare, getString(R.string.string_110)),
            ),
            onMenuAction = { id ->
                when (id) {
                    idCopy -> copySelection()
                    idShare -> shareSelection()
                    idSearchPixiv -> searchSelectionOnPixiv()
                    idSearchWeb -> searchSelectionOnWeb()
                    idTranslate -> translateSelection()
                    idHighlightParent -> pickHighlightColor()
                    idNote -> openNoteEditorForSelection()
                }
            },
        )
    }

    // 加载环随阅读器主题着色:牛皮纸/纯白等浅底用主题强调色即可看清,
    // 夜间/炭黑等深底同理。不确定模式下 track 不绘制,仍设一份低透明度
    // 同色 track 以保持与插画详情页进度环一致的配置。
    private fun applyLoadingTint(theme: ReaderTheme) {
        binding.readerLoading.setIndicatorColor(theme.accentColor)
        binding.readerLoading.trackColor = ColorUtils.setAlphaComponent(theme.accentColor, 0x33)
        // 用正文色而不是 secondaryTextColor：后者在牛皮纸这类低对比主题上几乎看不清。
        binding.readerProgressOverlayText.setTextColor(theme.textColor)
    }

    /**
     * 常驻阅读进度（#994）：横向翻页与纵向无极滚动都统一显示百分比 —— 纵向没有
     * 「页」的概念,百分比是两种模式唯一能对齐的口径。
     *
     * [percent] 传 null 表示「当前没有有效读数」(还没排完版 / 空章节),此时清空而
     * 不是留着上一章的旧数字骗人。
     */
    private fun setProgressPercent(percent: Int?) {
        progressOverlayText = percent?.let { "${it.coerceIn(0, 100)}%" }.orEmpty()
        refreshProgressOverlay()
    }

    /**
     * 只刷新可见性/文案,不改读数。底栏展开时不显示——那时底栏自己就有读数,再叠
     * 一层纯属重复。
     *
     * setText 一定要先比一次:纵向滚动是逐帧 onScrolled 回调,而百分比整本书也才变
     * 100 次,不拦住的话每帧都要重建一次 StaticLayout + 多分配一个 String。
     */
    private fun refreshProgressOverlay() {
        val show = ReaderSettings.showBottomProgress &&
            chrome?.isShown != true &&
            progressOverlayText.isNotEmpty()
        binding.readerProgressOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && binding.readerProgressOverlayText.text?.toString() != progressOverlayText) {
            binding.readerProgressOverlayText.text = progressOverlayText
        }
    }

    /**
     * 翻页模式的百分比按「已读完的页数 / 总页数」算,所以首页不是 0%、末页正好
     * 100%,和纵向滚到底同为 100%,两种模式来回切不会跳数。
     * totalPages 为 0 时返回 null(而不是去除以 0)。
     */
    private fun pagedPercent(pageIndex: Int, totalPages: Int): Int? {
        if (totalPages <= 0) return null
        return ((pageIndex.coerceIn(0, totalPages - 1) + 1) * 100) / totalPages
    }

    /**
     * 加载环要一直盖到「正文真正上屏」为止,不能只看 loadState。纵向模式在
     * loadState→Loaded 时 rebindScrollViewIfActive() 已经把 tokens 贴上去了;
     * 横向翻页模式还要等 pagination 在 novel-paginate 线程上排完版才 rv.bind(),
     * 万字小说这一段在中低端机上能到 1 秒,期间屏幕只剩背景色。
     */
    private fun updateLoadingVisibility() {
        val state = viewModel.loadState.value
        val contentReady = ReaderSettings.readingDirection == ReadingDirection.Vertical ||
            viewModel.pagination.value != null
        val showLoading = state is NovelReaderV3ViewModel.LoadState.Loading ||
            (state is NovelReaderV3ViewModel.LoadState.Loaded && !contentReady)
        binding.readerLoading.visibility = if (showLoading) View.VISIBLE else View.GONE
    }

    // ---- Observe ------------------------------------------------------------

    private fun observeReaderState(
        rv: NovelReaderView,
        tb: ReaderTopBar,
        bb: ReaderBottomBar,
        so: ReaderSearchOverlay,
        ch: ReaderChrome,
    ) {
        ReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            when (event) {
                ReaderSettings.ChangeEvent.Layout,
                ReaderSettings.ChangeEvent.Theme,
                -> {
                    pushStyleAndGeometryIfReady()
                    rebindScrollViewIfActive()
                    val t = ReaderTheme.findPresetById(ReaderSettings.themeId) ?: ReaderTheme.KRAFT
                    binding.root.setBackgroundColor(t.backgroundColor)
                    applyLoadingTint(t)
                    // showBottomProgress 走 Layout 事件,开关拨完立刻生效。
                    refreshProgressOverlay()
                }
                ReaderSettings.ChangeEvent.Flip -> applyFlipMode(rv, ch)
                ReaderSettings.ChangeEvent.IllustMix -> {
                    // 混排来源切换：VM 补拉取材 + 直接重排版（绕开 updateLayout 的
                    // style/geometry 去重——本设置不改排版样式，只改 token 流）。
                    viewModel.onIllustMixSettingChanged()
                    rebindScrollViewIfActive()
                }
                ReaderSettings.ChangeEvent.Interaction -> {
                    rv.setTouchLocked(ReaderSettings.touchLocked)
                    rv.setTapZoneReversed(ReaderSettings.tapZoneReversed)
                    binding.root.keepScreenOn = ReaderSettings.keepScreenOn
                }
                else -> Unit
            }
        }

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            updateLoadingVisibility()
            binding.readerError.visibility = if (state is NovelReaderV3ViewModel.LoadState.Error) View.VISIBLE else View.GONE
            if (state is NovelReaderV3ViewModel.LoadState.Error) binding.readerError.text = state.message
            if (state is NovelReaderV3ViewModel.LoadState.Loaded) {
                tb.setTitle(state.novel?.title ?: state.webNovel.title.orEmpty())
                // 系列按钮按当前小说是否归属系列动态显示。
                val seriesId = state.novel?.series?.id
                bb.setSeriesVisible(seriesId != null)
                loadWatchlistStateForSeries(seriesId)
                pushStyleAndGeometryIfReady()
                rebindScrollViewIfActive()
            }
        }

        viewModel.pagination.observe(viewLifecycleOwner) { pag ->
            updateLoadingVisibility()
            if (pag == null) return@observe
            if (ReaderSettings.readingDirection == ReadingDirection.Vertical) return@observe
            rv.setStyle(pag.style, pag.geometry)
            rv.bind(pag.pages, pag.startPageIndex)
            rv.setFlipMode(ReaderSettings.flipMode)
            bb.setProgress(pag.startPageIndex, pag.pages.size)
            setProgressPercent(pagedPercent(pag.startPageIndex, pag.pages.size))
        }

        viewModel.currentPageIndex.observe(viewLifecycleOwner) { index ->
            // Page-index events are paged-mode semantics. In vertical mode the
            // bottom bar is driven by NovelScrollReaderView.onScrollProgressChanged
            // instead — letting setProgress run here would flip the bar back to
            // page mode and zero out the scroll fraction.
            if (ReaderSettings.readingDirection == ReadingDirection.Vertical) return@observe
            val total = viewModel.pagination.value?.pages?.size ?: 0
            bb.setProgress(index, total)
            setProgressPercent(pagedPercent(index, total))
        }

        ObjectPool.get<Novel>(resolveNovelId()).observe(viewLifecycleOwner) { novel ->
            tb.setLiked(novel?.is_bookmarked == true)
            val title = novel?.title
            if (!title.isNullOrEmpty()) tb.setTitle(title)
        }

        viewModel.markerPage.observe(viewLifecycleOwner) { page ->
            tb.setMarked((page ?: 0) > 0)
        }

        // 混排取材是异步拉的：到货后重绑纵向滚动视图把插图上屏
        // （横向模式由 VM 内部 repaginate 覆盖，走 pagination observer）。
        viewModel.illustMixVersion.observe(viewLifecycleOwner) { version ->
            if ((version ?: 0) > 0) rebindScrollViewIfActive()
        }

        viewModel.annotations.observe(viewLifecycleOwner) { list ->
            annotationSpans = list.map { a ->
                HighlightSpan(
                    annotationId = a.annotationId,
                    absoluteStart = a.charStart,
                    absoluteEnd = a.charEnd,
                    color = a.color,
                    hasNote = a.note.isNotEmpty(),
                )
            }
            rebuildOverlays()
        }

        viewModel.searchResult.observe(viewLifecycleOwner) { result ->
            so.setCount(result.currentIndex, result.total)
            rebuildOverlays()
        }

        bb.setDarkMode(currentThemeIsDark())
    }

    // ---- Actions ------------------------------------------------------------

    private fun rebuildOverlays() {
        val hits = searchHitRanges()
        readerView?.setOverlays(
            PageOverlays(
                searchHits = hits,
                annotations = annotationSpans,
                selection = activeSelection,
            ),
        )
        scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.applySearchHighlights(hits)
    }

    private fun clearSelection() {
        activeSelection = null
        rebuildOverlays()
    }

    // ---- Scroll / paged mode switch ----------------------------------------

    private fun applyFlipMode(rv: NovelReaderView, chrome: ReaderChrome) {
        if (ReaderSettings.readingDirection == ReadingDirection.Vertical) {
            rv.visibility = View.GONE
            val sv = ensureScrollReaderView(chrome)
            sv.visibility = View.VISIBLE
            rebindScrollViewIfActive()
            // Force one progress emission so the bottom seekbar leaves paged
            // mode (page-x/y) and snaps to the current scroll fraction even
            // before the user touches the scroll view.
            sv.pushScrollProgressNow()
        } else {
            scrollReaderView?.let { sv ->
                viewModel.onScrollPositionChanged(sv.currentCharIndex())
                sv.visibility = View.GONE
            }
            rv.visibility = View.VISIBLE
            rv.setFlipMode(ReaderSettings.flipMode)
            // Invalidate dedup cache so pushStyle actually triggers re-pagination.
            // The cache keys don't include flipMode, so a mode-only change would
            // be suppressed without this reset.
            lastPushedSnapshot = null
            pushStyleAndGeometryIfReady()
        }
        // 纵向切横向时 rv 之前一直是 GONE(宽高为 0,从没排过版),pagination 还是
        // null,正文要等这次 repaginate 才有——补一次判定把加载环显示出来。
        updateLoadingVisibility()
    }

    private fun ensureScrollReaderView(chrome: ReaderChrome): NovelScrollReaderView {
        scrollReaderView?.let { return it }
        return NovelScrollReaderView(requireContext()).also { sv ->
            sv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            sv.visibility = View.GONE
            binding.readerStage.addView(sv)
            scrollReaderView = sv
            sv.onCenterTap = { chrome.toggle() }
            sv.onImageTap = { image -> openImageElement(image) }
            sv.onJumpTap = { target -> handleJumpTap(target) }
            sv.onCharIndexChanged = { charIndex -> viewModel.onScrollPositionChanged(charIndex) }
            sv.onScrollProgressChanged = { progress ->
                // Drive the bottom-bar SeekBar in vertical mode (issue: 纵向翻页底部
                // 进度条不联动). Paged mode is driven separately by currentPageIndex.
                bottomBar?.setScrollProgress(progress)
                setProgressPercent((progress.coerceIn(0f, 1f) * 100).toInt())
            }

            // Text selection — same menu as paged mode
            sv.selectionMenuEntries = listOf(
                1 to getString(R.string.action_copy), 2 to getString(R.string.string_110), 9 to getString(R.string.action_highlight), 20 to getString(R.string.action_note),
                5 to getString(R.string.action_translate), 3 to getString(R.string.action_search_pixiv), 4 to getString(R.string.action_search_web),
            )
            sv.onSelectionStarted = { absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text)
                chrome.hide()
            }
            sv.onSelectionChanged = { absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text)
            }
            sv.onSelectionEnded = { activeSelection = null }
            sv.onSelectionMenuAction = { id ->
                when (id) {
                    1 -> copySelection()
                    2 -> shareSelection()
                    3 -> searchSelectionOnPixiv()
                    4 -> searchSelectionOnWeb()
                    5 -> translateSelection()
                    9 -> pickHighlightColor()
                    20 -> openNoteEditorForSelection()
                }
            }
        }
    }

    private fun rebindScrollViewIfActive() {
        val sv = scrollReaderView ?: return
        if (sv.visibility != View.VISIBLE) return
        val loaded = viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded ?: return
        val ctx = context ?: return
        val snapshot = ReaderSettings.snapshot()
        val theme = ReaderTheme.findPresetById(snapshot.themeId) ?: ReaderTheme.KRAFT
        val style = TypeStyle.from(ctx, snapshot, theme)
        val density = resources.displayMetrics.density
        val horizontal = ReaderSettings.horizontalMarginDp * density
        val verticalMargin = ReaderSettings.verticalMarginDp * density
        val geom = PageGeometry(
            width = sv.width.coerceAtLeast(1),
            height = sv.height.coerceAtLeast(1),
            paddingLeft = horizontal,
            paddingTop = maxOf(topInsetPx.toFloat(), verticalMargin),
            paddingRight = horizontal,
            paddingBottom = maxOf(bottomInsetPx.toFloat(), verticalMargin),
        )
        // Preserve reading position across rebinds (e.g. font-size change).
        // On first bind (charAnchors empty), fall back to persisted progress.
        val currentChar = sv.currentCharIndex().takeIf { it > 0 }
            ?: ReaderProgressStore.loadCharIndex(viewModel.novelId)
        // displayTokens/displayImageResolver：混排插画只进展示链路，loaded.tokens 保持纯净。
        sv.bind(viewModel.displayTokens(), style, geom, viewModel.displayImageResolver())
        if (currentChar > 0) sv.jumpToCharIndex(currentChar)
        // Make sure the bottom seekbar gets at least one update even when
        // currentChar == 0 (fresh entry, no saved progress) — otherwise the
        // scroll-listener wouldn't fire until the user first scrolls.
        sv.pushScrollProgressNow()
    }

    private fun togglePixivBookmark() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 成功路径返回空串：请求还没发出去，此时报「已收藏」是骗用户。反馈由顶栏
            // 那颗爱心承担（它 observe ObjectPool 里的 Novel），失败时队列会把它拨回去。
            viewModel.toggleBookmark().takeIf { it.isNotEmpty() }?.let(Toaster::showShort)
        }
    }

    private fun togglePixivMarker() {
        viewLifecycleOwner.lifecycleScope.launch {
            Toaster.showShort(viewModel.toggleMarker())
        }
    }

    // 长按收藏按钮 → 跳「按标签收藏」自定义公开/私密 + 标签（issue #839）。
    private fun openTagBookmarkForCurrentNovel() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        val novel = ObjectPool.get<Novel>(novelId).value ?: return
        ceui.pixiv.ui.novel.openTagBookmarkForNovel(requireView(), novel)
    }

    private fun currentThemeIsDark(): Boolean =
        ReaderTheme.findPresetById(ReaderSettings.themeId)?.isDark == true

    /** 进作品（小说）详情页，供顶栏「更多」菜单的「作品详情」项调用。 */
    private fun openNovelDetailPage() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
            putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_DETAIL.key)
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    /**
     * 顶栏 ⋮ 菜单：作品相关（详情 / 评论 / 分享 / 复制）+ 阅读器相关（书签 / 保存位置 /
     * 追更 / 导出）。后者原本挂在底栏「更多」，底栏收窄后合并到这里。
     * 「批注」不再挂菜单项——顶栏已有常驻批注按钮（[ReaderTopBar.onAnnotationsClick]）。
     */
    private fun showTopMoreMenu() {
        if (isLocalSource) {
            // 本地 txt 没有作品详情 / 评论 / 分享链接，只留「复制正文」+ 阅读器项。
            showV3Menu {
                item(getString(R.string.menu_copy_novel_text), R.drawable.chat_ic_content_copy) {
                    copyNovelBodyToClipboard()
                }
                addReaderMenuItems()
            }
            return
        }
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        viewLifecycleOwner.lifecycleScope.launch {
            // CancellationException 必须重新抛出，否则 null 分支的 requireContext() 会在
            // fragment detach 后 crash（同 tryJumpSeriesNeighbor）。
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: runCatching { Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) } }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            if (novel == null) {
                Toaster.showShort(getString(R.string.msg_novel_loading))
                return@launch
            }
            showV3Menu {
                // 从下载管理直接进二级正文时,back stack 里没有一级详情;
                // 这里提供回到作品详情的入口,普通路径下也能当快捷跳转。
                item(getString(R.string.v3_label_artwork_details), R.drawable.ic_baseline_menu_book_24) {
                    openNovelDetailPage()
                }
                item(getString(R.string.view_comments), R.drawable.ic_baseline_comment_24) {
                    val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                        putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
                        putExtra(Params.NOVEL_ID, novelId.toInt())
                    }
                    startActivity(intent)
                }
                item(getString(R.string.string_110), R.drawable.ic_share_black_24dp) {
                    shareNovel(novel)
                }
                item(getString(R.string.menu_copy_link), R.drawable.ic_baseline_launch_24) {
                    if (ClipBoardUtils.setPrimaryClip(requireContext(), ClipData.newPlainText("pixiv-novel", NOVEL_URL_HEAD + novelId))) {
                        Toaster.showShort(getString(R.string.msg_link_copied))
                    } else {
                        Toaster.showShort(getString(R.string.msg_copy_failed))
                    }
                }
                item(getString(R.string.menu_copy_novel_text), R.drawable.chat_ic_content_copy) {
                    copyNovelBodyToClipboard()
                }
                addReaderMenuItems()
            }
        }
    }

    /**
     * 把当前小说正文塞进剪贴板。Pixiv 单篇上限约 10 万字 (~200KB UTF-16)，
     * 远低于 Binder 事务的 ~1MB 上限，正常不会崩；但极端长内容 / OEM 剪贴板服务
     * 抽风仍可能抛 [android.os.TransactionTooLargeException] 之类，catch 住 toast 兜底，
     * 不让进程挂掉。
     */
    private fun copyNovelBodyToClipboard() {
        val text = viewModel.buildBodyPlainText()
        if (text.isNullOrEmpty()) {
            Toaster.showShort(getString(R.string.msg_novel_not_ready))
            return
        }
        try {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("pixiv-novel-text", text))
            Toaster.showShort(getString(R.string.msg_novel_text_copied, text.length))
        } catch (t: Throwable) {
            Timber.w(t, "copy novel text to clipboard failed, len=${text.length}")
            Toaster.showLong(getString(R.string.msg_copy_failed))
        }
    }

    /** 阅读器自身的菜单项（书签 / 保存位置 / 追更 / 导出），由 [showTopMoreMenu] 两个分支共用。 */
    private fun V3MenuBuilder.addReaderMenuItems() {
        item(getString(R.string.menu_bookmarks), R.drawable.ic_baseline_bookmark_24) {
            showBookmarksSheet()
        }
        item(getString(R.string.menu_save_position), R.drawable.ic_baseline_bookmark_24) {
            // 纵向滚动模式没有 pagination（rv 从没排过版），按滚动位置的 charIndex 存；
            // 横向翻页仍按当前页存（#1038：此前纵向下静默存不上、toast 却报已保存）。
            val sv = scrollReaderView
            if (sv != null && sv.visibility == View.VISIBLE) {
                viewModel.addBookmarkAtCharIndex(sv.currentCharIndex())
            } else {
                viewModel.addBookmarkAtCurrentPage(readerView?.currentPageIndex() ?: 0)
            }
            Toaster.showShort(getString(R.string.msg_bookmark_saved))
        }
        // 追更 (加入/取消)：仅当前小说有所属系列时才挂条目，文案按当前
        // [watchlistAdded] 在「加入追更列表」/「取消追更」之间切。点击走
        // 乐观更新，失败由 toggleWatchlist 内部回滚 + toast。
        if (watchlistSeriesId != null) {
            val label = getString(
                if (watchlistAdded) R.string.reader_menu_watchlist_remove
                else R.string.reader_menu_watchlist_add
            )
            val icon = if (watchlistAdded) R.drawable.icon_liked else R.drawable.icon_not_liked
            item(label, icon) { toggleWatchlist() }
        }
        // 本地 txt 已经是 txt，再导出无意义；且 NovelHeaderRenderer 需要非空
        // novel（本地源 novel 为 null），直接不挂导出项。
        if (!isLocalSource) {
            item(getString(R.string.menu_export), R.drawable.ic_baseline_get_app_24) {
                if (viewModel.loadState.value !is NovelReaderV3ViewModel.LoadState.Loaded) {
                    Toaster.showShort(getString(R.string.msg_novel_not_ready))
                    return@item
                }
                val format = NovelExportManager.resolveConfiguredFormat()
                if (format != null) {
                    executeExport(format, allowAutoEpub = true)
                } else {
                    showExportSheet()
                }
            }
        }
    }

    private fun showExportSheet() {
        ExportSheet().show(childFragmentManager, ExportSheet.TAG)
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        executeExport(format)
    }

    private fun executeExport(format: ExportFormat, allowAutoEpub: Boolean = false) {
        Toaster.showShort(getString(R.string.msg_export_start, getString(format.displayNameResId)))
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = viewModel.exportNovel(format, allowAutoEpub)) {
                is ExportResult.Success -> Toaster.showLong(getString(R.string.msg_export_success, result.displayPath))
                is ExportResult.Failure -> Toaster.showLong(getString(R.string.msg_export_fail, result.message))
            }
        }
    }

    private fun pickHighlightColor() {
        val sel = activeSelection ?: return
        val options = listOf(getString(R.string.highlight_yellow) to HighlightColor.Yellow, getString(R.string.highlight_green) to HighlightColor.Green, getString(R.string.highlight_pink) to HighlightColor.Pink, getString(R.string.highlight_blue) to HighlightColor.Blue)
        WitDialog.MenuDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_choose_highlight_color))
            .addItems(options.map { it.first }.toTypedArray()) { dialog, which ->
                viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, options[which].second.argb)
                Toaster.showShort(getString(R.string.msg_highlighted))
                clearSelection()
                dialog.dismiss()
            }
            .addAction(getString(R.string.action_cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun openNoteEditorForSelection() {
        val sel = activeSelection ?: return
        NoteEditorDialog.newInstance(
            annotationId = 0L,
            charStart = sel.absoluteStart,
            charEnd = sel.absoluteEnd,
            excerpt = sel.text,
            color = HighlightColor.Yellow.argb,
        ).show(childFragmentManager, NoteEditorDialog.TAG)
    }

    private fun editAnnotation(entry: NovelAnnotationEntity) {
        NoteEditorDialog.newInstance(
            annotationId = entry.annotationId,
            charStart = entry.charStart,
            charEnd = entry.charEnd,
            excerpt = entry.excerpt,
            existingNote = entry.note,
            color = entry.color,
            showDelete = entry.note.isNotEmpty(),
        ).show(childFragmentManager, NoteEditorDialog.TAG)
    }

    override fun onNoteSaved(annotationId: Long, charStart: Int, charEnd: Int, excerpt: String, noteText: String, color: Int) {
        if (noteText.isNotEmpty()) {
            viewModel.saveNote(annotationId, charStart, charEnd, excerpt, noteText, color)
            Toaster.showShort(getString(R.string.msg_note_saved))
        }
        if (annotationId == 0L) clearSelection()
    }

    override fun onNoteDeleted(annotationId: Long) {
        viewModel.deleteAnnotation(annotationId)
    }

    private fun showAnnotationsSheet() {
        AnnotationsSheet().show(childFragmentManager, AnnotationsSheet.TAG)
    }

    override fun onJumpToAnnotation(entry: NovelAnnotationEntity) {
        navigateToCharIndex(entry.charStart)
    }

    override fun onEditAnnotation(entry: NovelAnnotationEntity) {
        editAnnotation(entry)
    }

    override fun onDeleteAnnotation(entry: NovelAnnotationEntity) {
        viewModel.deleteAnnotation(entry.annotationId)
    }

    private fun showBookmarksSheet() {
        BookmarksSheet().show(childFragmentManager, BookmarksSheet.TAG)
    }

    override fun onJumpToBookmark(entry: NovelBookmarkEntity) {
        navigateToCharIndex(entry.charIndex)
    }

    override fun onDeleteBookmark(entry: NovelBookmarkEntity) {
        viewModel.deleteBookmark(entry.bookmarkId)
    }

    // ---- Search -------------------------------------------------------------

    private fun searchHitRanges(): List<HighlightRange> {
        val result = viewModel.searchResult.value ?: return emptyList()
        return result.hits.mapIndexed { i, hit ->
            val current = i == result.currentIndex
            HighlightRange(
                absoluteStart = hit.absoluteStart,
                absoluteEnd = hit.absoluteEnd,
                color = if (current) COLOR_SEARCH_CURRENT else COLOR_SEARCH_OTHER,
                isCurrent = current,
            )
        }
    }

    private fun runSearch(query: String) {
        viewModel.performSearch(query, searchRegex)
        viewModel.searchResult.value?.currentHit?.let { goToHitDirect(it) }
    }

    private fun jumpToHit(delta: Int) {
        (if (delta > 0) viewModel.nextSearchHit() else viewModel.prevSearchHit())?.let { goToHitDirect(it) }
    }

    private fun goToHitDirect(hit: SearchHit) {
        navigateToCharIndex(hit.absoluteStart)
    }

    private fun showSearchHitsSheet() {
        val result = viewModel.searchResult.value ?: return
        if (result.hits.isEmpty()) return
        val query = binding.readerSearchOverlay.editSearchQuery.text?.toString().orEmpty()
        SearchHitsSheet.newInstance(query).show(childFragmentManager, SearchHitsSheet.TAG)
    }

    override fun onSearchHitSelected(hit: SearchHit, index: Int) {
        viewModel.setSearchIndex(index)
        goToHitDirect(hit)
    }

    /** `[jump:N]` button → resolve target page to a char offset and navigate.
     *  Out-of-range or unknown targets show a toast instead of failing silently. */
    private fun handleJumpTap(target: Int) {
        val toks = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.tokens
            ?: return
        val charIndex = ceui.pixiv.ui.novel.reader.paginate.ContentParser.resolveJumpTarget(toks, target)
        if (charIndex == null) {
            Toaster.showShort(getString(R.string.msg_jump_target_invalid))
            return
        }
        navigateToCharIndex(charIndex, animate = true)
    }

    /** Unified jump: works in both paged and scroll mode. */
    private fun navigateToCharIndex(charIndex: Int, animate: Boolean = false) {
        viewModel.jumpToCharIndex(charIndex)
        if (scrollReaderView?.visibility == View.VISIBLE) {
            if (animate) scrollReaderView?.scrollToCharIndex(charIndex)
            else scrollReaderView?.jumpToCharIndex(charIndex)
        } else {
            val pageIdx = viewModel.pagination.value?.pages
                ?.indexOfFirst { it.charEnd >= charIndex }?.coerceAtLeast(0) ?: 0
            readerView?.goToPage(pageIdx, animate = animate)
        }
    }

    // ---- Chapter navigation -------------------------------------------------

    private fun showChapterDrawer() {
        val outline = viewModel.getChapterOutline()
        if (outline.isEmpty()) {
            Toaster.showShort(getString(R.string.msg_no_chapters))
            return
        }
        val currentStart = scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.currentCharIndex()
            ?: viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart
            ?: 0
        ChapterListSheet.newInstance(currentStart).show(childFragmentManager, ChapterListSheet.TAG)
    }

    override fun onChapterSelected(entry: ChapterOutlineEntry) {
        navigateToCharIndex(entry.sourceStart)
    }

    /**
     * 系列单篇切换 sheet。仅在当前小说归属一个系列时可用（按钮本身也会因
     * `setSeriesVisible(false)` 隐藏，这里再 guard 一次防护并发重入）。
     * 选中后 finish 当前 reader 并启动新的 reader activity，跟
     * [tryJumpSeriesNeighbor] 的接力策略一致。
     */
    private fun showSeriesSheet() {
        val novelId = resolveNovelId()
        val novel = ObjectPool.get<Novel>(novelId).value ?: return
        val sid = novel.series?.id ?: return
        SeriesListSheet.newInstance(
            seriesId = sid,
            currentNovelId = novelId,
            seriesTitle = novel.series?.title,
        ).show(childFragmentManager, SeriesListSheet.TAG)
    }

    override fun onSeriesNovelSelected(novel: Novel) {
        val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
            putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
            putExtra(Params.NOVEL_ID, novel.id)
        }
        startActivity(intent)
        activity?.finish()
    }

    private fun jumpChapter(forward: Boolean) {
        val outline = viewModel.getChapterOutline()
        if (outline.isNotEmpty()) {
            val currentChar = scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.currentCharIndex()
                ?: viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart
            if (currentChar != null) {
                // currentChar 是阅读位置而非章首，所以「上一章」不能用
                // lastOrNull { sourceStart < currentChar }——在章节中段它会命中
                // 当前章自己的章首(进度归零),而不是真正的上一章。先定位当前章
                // 下标再 ±1,跟「下一章」方向对称。
                val currentIdx = outline.indexOfLast { it.sourceStart <= currentChar }
                val target = if (forward) outline.getOrNull(currentIdx + 1)
                else outline.getOrNull(currentIdx - 1)
                if (target != null) {
                    navigateToCharIndex(target.sourceStart, animate = true)
                    return
                }
            }
        }
        // outline 没命中：要么整本没 [chapter:] 标记，要么已经到本卷首尾——
        // 若属于同一系列，切换到上/下一篇。
        if (!tryJumpSeriesNeighbor(forward)) {
            if (outline.isEmpty()) {
                if (forward) readerView?.flipForward() else readerView?.flipBackward()
            } else {
                Toaster.showShort(if (forward) getString(R.string.msg_last_chapter) else getString(R.string.msg_first_chapter))
            }
        }
    }

    /**
     * 系列作品的单篇切换——在没有章节 outline 或 outline 已走到头时接力。
     * 整条系列走 [SeriesCache]（进程内缓存，最多 10 页），找当前位置跳到邻居；只有第一次
     * 翻页真正打网络，之后全命中。返回 true 表示已发起跳转，调用方不要再 toast。
     */
    private fun tryJumpSeriesNeighbor(forward: Boolean): Boolean {
        val novelId = resolveNovelId()
        if (novelId == 0L) return false
        val seriesId = ObjectPool.get<Novel>(novelId).value?.series?.id ?: return false
        viewLifecycleOwner.lifecycleScope.launch {
            // CancellationException 必须重新抛出（用户在请求期间退出 reader → view 销毁 →
            // scope 取消）：runCatching 吞掉它会继续执行到下面 neighbor == null 分支的
            // requireContext()，而此时 fragment 已 detach，必抛 IllegalStateException crash。
            val neighbor = runCatching {
                // 整条系列走 SeriesCache 进程内缓存：第一次翻页拉一次，之后翻页 / 开选话
                // sheet 全部命中，不再每次重拉。单话实体直接存在缓存里，从 items 取。
                val items = SeriesCache.loadNovelSeries(seriesId).items
                val idx = items.indexOfFirst { it.id == novelId }
                if (idx < 0) null
                else if (forward) items.getOrNull(idx + 1) else items.getOrNull(idx - 1)
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (neighbor == null || neighbor.id == 0L) {
                Toaster.showShort(if (forward) getString(R.string.msg_last_chapter) else getString(R.string.msg_first_chapter))
                return@launch
            }
            Toaster.showShort(getString(
                    if (forward) R.string.msg_jump_next_in_series else R.string.msg_jump_prev_in_series,
                    neighbor.title.orEmpty(),
                ))
            val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
                putExtra(Params.NOVEL_ID, neighbor.id)
            }
            startActivity(intent)
            activity?.finish()
        }
        return true
    }

    // ---- Watchlist (加入追更列表) --------------------------------------------
    //
    // pixiv 原生 series header 有"追更"开关。这里把同一动作收进 reader 顶栏
    // ⋮ 菜单（showTopMoreMenu / addReaderMenuItems），避免常驻底栏挤占视图。状态来源 / API：
    //   - GET /v1/novel/series/{id}   → novel_series_detail.watchlist_added (初始态)
    //   - POST /v1/novel/series/watchlist/add | delete
    // 进 reader 时预拉一次，菜单弹出时按 [watchlistAdded] 决定 item 文案；点击
    // 走乐观更新（状态先翻，失败回滚 + toast）。

    /** loadState→Loaded 时调；同一 seriesId 不重复拉。 */
    private fun loadWatchlistStateForSeries(seriesId: Long?) {
        if (seriesId == null) {
            watchlistSeriesId = null
            watchlistAdded = false
            return
        }
        if (watchlistSeriesId == seriesId) return
        watchlistSeriesId = seriesId
        watchlistAdded = false
        viewLifecycleOwner.lifecycleScope.launch {
            val added = runCatching {
                Client.appApi.getNovelSeries(seriesId).novel_series_detail?.watchlist_added == true
            }.getOrDefault(false)
            // seriesId 在协程跑完之前没变才回填，避免快速切章造成错位
            if (watchlistSeriesId != seriesId) return@launch
            watchlistAdded = added
        }
    }

    private fun toggleWatchlist() {
        val seriesId = watchlistSeriesId ?: return
        val nextAdded = !watchlistAdded
        // 乐观更新：本地态先翻，失败再回滚。菜单是 popup，不需要更新 UI，
        // 只靠 toast 反馈结果。
        watchlistAdded = nextAdded
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (nextAdded) Client.appApi.postWatchlistNovelAdd(seriesId.toInt())
                else Client.appApi.postWatchlistNovelDelete(seriesId.toInt())
                Toaster.showShort(if (nextAdded) R.string.reader_watchlist_added_toast else R.string.reader_watchlist_removed_toast)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                Timber.e(ex, "toggleWatchlist failed")
                watchlistAdded = !nextAdded
                Toaster.showShort(R.string.reader_watchlist_toggle_failed)
            }
        }
    }

    // ---- Selection actions --------------------------------------------------

    private fun copySelection() {
        val sel = activeSelection ?: return
        if (ClipBoardUtils.setPrimaryClip(requireContext(), ClipData.newPlainText("novel selection", sel.text))) {
            Toaster.showShort(getString(R.string.msg_copied))
        } else {
            Toaster.showShort(getString(R.string.msg_copy_failed))
        }
    }

    private fun shareSelection() {
        val sel = activeSelection ?: return
        val novel = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.novel
        val author = novel?.user?.name.orEmpty()
        val title = novel?.title.orEmpty()
        val body = if (title.isEmpty()) sel.text else "「${sel.text}」\n\n—— $title${if (author.isNotEmpty()) " / $author" else ""}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, getString(R.string.chooser_share_selection)))
    }

    private fun searchSelectionOnPixiv() {
        val query = activeSelection?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pixiv.net/tags/${Uri.encode(query)}/novels"))) }
            .onFailure { Toaster.showShort(getString(R.string.msg_no_browser)) }
    }

    private fun searchSelectionOnWeb() {
        val query = activeSelection?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", query) }) }
            .onFailure { Toaster.showShort(getString(R.string.msg_no_app)) }
    }

    private fun translateSelection() {
        val sel = activeSelection ?: return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain"; putExtra(Intent.EXTRA_PROCESS_TEXT, sel.text); putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, getString(R.string.chooser_translate)))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=${appTranslateTargetLang()}&text=${Uri.encode(sel.text)}&op=translate"))) }
                .onFailure { Toaster.showShort(getString(R.string.msg_no_translate_app)) }
        }
    }

    // ---- Layout push --------------------------------------------------------

    private fun pushStyleAndGeometryIfReady() {
        val ctx = context ?: return
        val rv = readerView ?: return
        val w = rv.width; val h = rv.height
        if (w <= 0 || h <= 0) return
        val snapshot = ReaderSettings.snapshot()
        val themeIsDark = currentThemeIsDark()
        if (snapshot == lastPushedSnapshot && w == lastPushedWidth && h == lastPushedHeight && themeIsDark == lastPushedThemeIsDark && topInsetPx == lastPushedTopInset && bottomInsetPx == lastPushedBottomInset) return
        lastPushedSnapshot = snapshot; lastPushedWidth = w; lastPushedHeight = h
        lastPushedThemeIsDark = themeIsDark; lastPushedTopInset = topInsetPx; lastPushedBottomInset = bottomInsetPx
        val density = resources.displayMetrics.density
        val horizontal = ReaderSettings.horizontalMarginDp * density
        val verticalMargin = ReaderSettings.verticalMarginDp * density
        viewModel.updateLayout(
            TypeStyle.from(ctx, snapshot, ReaderTheme.findPresetById(ReaderSettings.themeId) ?: ReaderTheme.KRAFT),
            PageGeometry(w, h, horizontal, maxOf(topInsetPx.toFloat(), verticalMargin), horizontal, maxOf(bottomInsetPx.toFloat(), verticalMargin)),
        )
    }

    // ---- Image tap ----------------------------------------------------------

    private fun openImageElement(image: ceui.pixiv.ui.novel.reader.model.PageElement.Image) {
        when (image.imageType) {
            ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.UploadedImage -> {
                val url = image.imageUrl ?: return
                ImageUrlViewer.open(requireContext(), url, saveName = "novel_${resolveNovelId()}_upload_${image.resourceId}")
            }
            ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.PixivImage -> {
                if (image.resourceId <= 0L) return
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { Client.appApi.getIllust(image.resourceId).illust }
                        .getOrNull()?.let { illust ->
                            val uuid = UUID.randomUUID().toString()
                            Container.get().addPageToMap(PageData(uuid, null, listOf(illust)))
                            startActivity(Intent(requireContext(), VActivity::class.java).apply {
                                putExtra(Params.POSITION, 0); putExtra(Params.PAGE_UUID, uuid)
                            })
                        }
                }
            }
        }
    }

    // ---- Volume key flip ----------------------------------------------------

    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!ReaderSettings.volumeKeyFlip) return false
        val forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (scrollReaderView?.visibility == View.VISIBLE) {
            scrollReaderView?.scrollByPage(forward)
        } else {
            if (forward) readerView?.flipForward() else readerView?.flipBackward()
        }
        return true
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onDestroyView() {
        super.onDestroyView()
        imageSource?.clear()
        imageSource = null
        readerView = null
        scrollReaderView = null
        bottomBar = null
        // chrome 持有顶/底栏的 ViewBinding,不放会把整棵已销毁的 view 树吊在
        // fragment 上（Activity Embedding 下 fragment 比 view 活得久）。
        chrome?.onVisibilityChanged = null
        chrome = null
    }

    private fun resolveNovelId(): Long {
        arguments?.let { args ->
            val localKey = args.getString(ARG_LOCAL_KEY)
            if (!localKey.isNullOrEmpty()) {
                return ceui.pixiv.ui.novel.local.LocalLibraryStore.novelIdFor(localKey)
            }
            val idLong = args.getLong(ARG_NOVEL_ID, 0L)
            if (idLong != 0L) return idLong
            @Suppress("DEPRECATION")
            val bean = args.getSerializable(Params.CONTENT) as? Novel
            if (bean != null) return bean.id.toLong()
        }
        return 0L
    }

    companion object {
        private const val ARG_NOVEL_ID = "novel_id"
        private const val ARG_LOCAL_URI = "local_uri"
        private const val ARG_LOCAL_TITLE = "local_title"
        private const val ARG_LOCAL_KEY = "local_key"
        private const val COLOR_SEARCH_CURRENT = 0xAAFF9800.toInt() // opaque orange
        private const val COLOR_SEARCH_OTHER = 0x66FFEB3B           // semi-transparent yellow

        @JvmStatic
        fun newInstance(novelBean: Novel): NovelReaderV3Fragment {
            return NovelReaderV3Fragment().apply {
                arguments = Bundle().apply {
                    putSerializable(Params.CONTENT, novelBean)
                    putLong(ARG_NOVEL_ID, novelBean.id.toLong())
                }
            }
        }

        @JvmStatic
        fun newInstance(novelId: Long): NovelReaderV3Fragment {
            return NovelReaderV3Fragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_NOVEL_ID, novelId)
                }
            }
        }

        /**
         * 本地 txt 阅读：[uri] 是 SAF document content:// URI，[title] 取自文件名，
         * [idKey] 是书库内相对路径（[ceui.pixiv.ui.novel.local.LocalLibraryStore.novelIdFor]
         * 据此派生稳定负数 novelId，绑定进度/标注/书签）。
         */
        @JvmStatic
        fun newInstanceLocal(uri: String, title: String?, idKey: String?): NovelReaderV3Fragment {
            return NovelReaderV3Fragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_LOCAL_URI, uri)
                    putString(ARG_LOCAL_TITLE, title)
                    putString(ARG_LOCAL_KEY, idKey ?: title ?: uri)
                }
            }
        }
    }
}
