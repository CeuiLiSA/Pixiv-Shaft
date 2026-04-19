package ceui.pixiv.ui.novel.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import ceui.lisa.R
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.feature.SearchEngine
import ceui.pixiv.ui.novel.reader.feature.TextHitTester
import ceui.pixiv.ui.novel.reader.model.HighlightColor
import ceui.pixiv.ui.novel.reader.model.HighlightSpan
import ceui.pixiv.ui.novel.reader.model.SearchHit
import ceui.pixiv.ui.novel.reader.model.TextSelection
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.GlideImageBitmapSource
import ceui.pixiv.ui.novel.reader.render.HighlightRange
import ceui.pixiv.ui.novel.reader.render.NovelReaderView
import ceui.pixiv.ui.novel.reader.render.PageOverlays
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import ceui.pixiv.ui.novel.reader.ui.AnnotationsSheet
import ceui.pixiv.ui.novel.reader.ui.BookmarksSheet
import ceui.pixiv.ui.novel.reader.ui.ChapterDrawerDialog
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.novel.reader.ui.NoteEditorDialog
import ceui.pixiv.ui.novel.reader.ui.ReaderBottomBar
import ceui.pixiv.ui.novel.reader.ui.ReaderChrome
import ceui.pixiv.ui.novel.reader.ui.ReaderSearchOverlay
import ceui.pixiv.ui.novel.reader.ui.ReaderSelectionToolbar
import ceui.pixiv.ui.novel.reader.ui.ReaderSettingsPanel
import ceui.pixiv.ui.novel.reader.ui.ReaderTopBar
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 reader entry. Wires NovelReaderView → ViewModel → Paginator, listens to
 * [ReaderSettings] for live re-pagination, and surfaces the top/bottom chrome
 * (bars + progress slider) via [ReaderChrome]. Panels / drawers / overlays
 * plug in through the bar callbacks as they ship.
 */
class NovelReaderV3Fragment : Fragment() {

    private val viewModel: NovelReaderV3ViewModel by viewModels {
        NovelReaderV3ViewModel.factory(resolveNovelId())
    }

    private lateinit var rootView: View
    private lateinit var readerView: NovelReaderView
    private lateinit var stage: FrameLayout
    private lateinit var loading: ProgressBar
    private lateinit var error: TextView

    private lateinit var topBar: ReaderTopBar
    private lateinit var bottomBar: ReaderBottomBar
    private lateinit var chrome: ReaderChrome
    private lateinit var searchOverlay: ReaderSearchOverlay
    private lateinit var selectionToolbar: ReaderSelectionToolbar

    private var imageSource: GlideImageBitmapSource? = null

    private var searchHits: List<SearchHit> = emptyList()
    private var currentHitIndex: Int = -1
    private var searchRegex: Boolean = false

    private var activeSelection: TextSelection? = null
    private var annotationSpans: List<HighlightSpan> = emptyList()

    // Cache to avoid spurious re-pagination — TypeStyle contains fresh Paint
    // instances each time so plain `==` always mismatches; we gate on the
    // ReaderSettings snapshot + reader view bounds instead.
    private var lastPushedSnapshot: ReaderSettings.Snapshot? = null
    private var lastPushedWidth: Int = 0
    private var lastPushedHeight: Int = 0
    private var lastPushedThemeIsDark: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_novel_reader_v3, container, false).also { rootView = it }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loading = view.findViewById(R.id.reader_loading)
        error = view.findViewById(R.id.reader_error)
        stage = view.findViewById(R.id.reader_stage)

        readerView = NovelReaderView(requireContext()).also { rv ->
            rv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            stage.addView(rv)
        }

        imageSource = GlideImageBitmapSource(requireContext()) { _ ->
            readerView.invalidate()
        }.also { readerView.setBitmapSource(it) }

        topBar = ReaderTopBar(view.findViewById(R.id.reader_top_bar))
        bottomBar = ReaderBottomBar(view.findViewById(R.id.reader_bottom_bar))
        chrome = ReaderChrome(topBar, bottomBar)
        searchOverlay = ReaderSearchOverlay(view.findViewById(R.id.reader_search_overlay))
        selectionToolbar = ReaderSelectionToolbar(view.findViewById(R.id.reader_selection_toolbar))

        wireTopBar()
        wireBottomBar()
        wireReaderView()
        wireSearchOverlay()
        wireSelectionToolbar()

        observeReaderState()

        applyInteractionSettings()
        viewModel.load()
    }

    private fun wireReaderView() {
        readerView.onTapCenter = {
            if (activeSelection != null) clearSelection() else chrome.toggle()
        }
        readerView.onLongPressAt = { x, y -> beginSelectionAt(x, y) }
        readerView.onDoubleTapAt = { _, _ ->
            Toast.makeText(requireContext(), "双击放大（Phase 3 接入）", Toast.LENGTH_SHORT).show()
        }
        readerView.onEdgeHit = { /* edge feedback: vibrate later */ }
        readerView.onPageChanged = { index ->
            viewModel.onPageChanged(index)
            if (activeSelection != null) clearSelection()
        }

        readerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            pushStyleAndGeometryIfReady()
        }
    }

    private fun wireTopBar() {
        topBar.onBackClick = { activity?.finish() }
        topBar.onBookmarkClick = { togglePixivBookmark() }
        topBar.onMoreClick = {
            Toast.makeText(requireContext(), "更多菜单（Phase 2/3 接入分享、下载、评论）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wireBottomBar() {
        bottomBar.onPrevChapter = { jumpChapter(forward = false) }
        bottomBar.onNextChapter = { jumpChapter(forward = true) }
        bottomBar.onChaptersClick = { showChapterDrawer() }
        bottomBar.onSettingsClick = {
            ReaderSettingsPanel().show(childFragmentManager, ReaderSettingsPanel.TAG)
        }
        bottomBar.onThemeToggleClick = { toggleDayNightTheme() }
        bottomBar.onSearchClick = { openSearch() }
        bottomBar.onMoreClick = { showReaderOverflowMenu() }
        bottomBar.onSeekCommit = { pageIndex -> readerView.goToPage(pageIndex, animate = false) }
    }

    private fun observeReaderState() {
        ReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            when (event) {
                ReaderSettings.ChangeEvent.Layout,
                ReaderSettings.ChangeEvent.Theme,
                -> pushStyleAndGeometryIfReady()
                ReaderSettings.ChangeEvent.Flip -> readerView.setFlipMode(ReaderSettings.flipMode)
                ReaderSettings.ChangeEvent.Interaction -> applyInteractionSettings()
                else -> Unit
            }
        }

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            loading.visibility = if (state is NovelReaderV3ViewModel.LoadState.Loading) View.VISIBLE else View.GONE
            error.visibility = if (state is NovelReaderV3ViewModel.LoadState.Error) View.VISIBLE else View.GONE
            if (state is NovelReaderV3ViewModel.LoadState.Error) error.text = state.message
            if (state is NovelReaderV3ViewModel.LoadState.Loaded) {
                topBar.setTitle(state.novel?.title ?: state.webNovel.title.orEmpty())
                pushStyleAndGeometryIfReady()
            }
        }

        viewModel.pagination.observe(viewLifecycleOwner) { pag ->
            if (pag == null) return@observe
            Timber.tag("NovelReaderV3").d(
                "pagination observed: ${pag.pages.size} pages, startIndex=${pag.startPageIndex}",
            )
            readerView.setStyle(pag.style, pag.geometry)
            readerView.bind(pag.pages, pag.startPageIndex)
            readerView.setFlipMode(ReaderSettings.flipMode)
            bottomBar.setProgress(pag.startPageIndex, pag.pages.size)
        }

        viewModel.currentPageIndex.observe(viewLifecycleOwner) { index ->
            val total = viewModel.pagination.value?.pages?.size ?: 0
            bottomBar.setProgress(index, total)
        }

        ObjectPool.get<Novel>(resolveNovelId()).observe(viewLifecycleOwner) { novel ->
            topBar.setBookmarked(novel?.is_bookmarked == true)
            val title = novel?.title
            if (!title.isNullOrEmpty()) topBar.setTitle(title)
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

        bottomBar.setDarkMode(currentThemeIsDark())
    }

    private fun rebuildOverlays() {
        readerView.setOverlays(
            PageOverlays(
                searchHits = searchHitRanges(),
                annotations = annotationSpans,
                selection = activeSelection,
            ),
        )
    }

    private fun togglePixivBookmark() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val novel = ObjectPool.get<Novel>(novelId).value
                    ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
                novel ?: return@runCatching
                if (novel.is_bookmarked == true) {
                    Client.appApi.removeNovelBookmark(novelId)
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = false,
                            total_bookmarks = novel.total_bookmarks?.minus(1),
                        ),
                    )
                    Toast.makeText(requireContext(), "取消收藏", Toast.LENGTH_SHORT).show()
                } else {
                    Client.appApi.addNovelBookmark(novelId, Params.TYPE_PUBLIC)
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = true,
                            total_bookmarks = novel.total_bookmarks?.plus(1),
                        ),
                    )
                    Toast.makeText(requireContext(), "收藏成功", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { ex ->
                Timber.tag("NovelReaderV3").e(ex)
                Toast.makeText(requireContext(), "操作失败：${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleDayNightTheme() {
        val current = ReaderSettings.themeId
        val isDark = currentThemeIsDark()
        ReaderSettings.themeId = if (isDark) ReaderTheme.WHITE.id else ReaderTheme.NIGHT.id
        bottomBar.setDarkMode(!isDark)
        Timber.tag("NovelReaderV3").d("Theme toggled: $current -> ${ReaderSettings.themeId}")
    }

    private fun currentThemeIsDark(): Boolean {
        return ReaderTheme.findPresetById(ReaderSettings.themeId)?.isDark == true
    }

    private fun wireSelectionToolbar() {
        selectionToolbar.onCopy = { copySelection() }
        selectionToolbar.onShare = { shareSelection() }
        selectionToolbar.onQuoteCard = {
            Toast.makeText(requireContext(), "金句卡（Phase 4.#22 接入）", Toast.LENGTH_SHORT).show()
        }
        selectionToolbar.onSearchPixiv = { searchSelectionOnPixiv() }
        selectionToolbar.onSearchWeb = { searchSelectionOnWeb() }
        selectionToolbar.onTranslate = { translateSelection() }
        selectionToolbar.onHighlight = { color -> saveHighlight(color) }
        selectionToolbar.onNote = { openNoteEditorForSelection() }
        selectionToolbar.onDismiss = { clearSelection() }
    }

    private fun beginSelectionAt(x: Float, y: Float) {
        val pages = viewModel.pagination.value?.pages ?: return
        val geometry = viewModel.pagination.value?.geometry ?: return
        val currentIndex = readerView.currentPageIndex()
        val page = pages.getOrNull(currentIndex) ?: return
        val hit = TextHitTester.hit(page, geometry.paddingLeft, x, y) ?: return
        val webNovel = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.webNovel ?: return
        val source = webNovel.text.orEmpty()
        if (source.isEmpty()) return
        val range = TextHitTester.initialSelectionAt(source, hit)
        val start = range.first
        val end = range.last + 1
        val text = source.substring(start.coerceIn(0, source.length), end.coerceIn(0, source.length))
        val selection = TextSelection(start, end, text)
        activeSelection = selection
        rebuildOverlays()
        val pos = TextHitTester.screenPosition(page, geometry.paddingLeft, start)
        val anchorX = pos?.x ?: (readerView.width / 2f)
        val anchorY = pos?.y ?: y
        selectionToolbar.showAt(anchorX, anchorY, readerView.width, readerView.height)
        chrome.hide()
    }

    private fun clearSelection() {
        activeSelection = null
        selectionToolbar.hide()
        rebuildOverlays()
    }

    private fun saveHighlight(color: HighlightColor) {
        val sel = activeSelection ?: return
        viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, color.argb)
        Toast.makeText(requireContext(), "已高亮", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun openNoteEditorForSelection() {
        val sel = activeSelection ?: return
        val excerptText = sel.text
        NoteEditorDialog().apply {
            configure(existingNote = "", excerpt = excerptText) { noteText ->
                if (noteText.isNotEmpty()) {
                    viewModel.saveNote(
                        annotationId = 0L,
                        charStart = sel.absoluteStart,
                        charEnd = sel.absoluteEnd,
                        excerpt = excerptText,
                        note = noteText,
                        colorArgb = HighlightColor.Yellow.argb,
                    )
                    Toast.makeText(requireContext(), "笔记已保存", Toast.LENGTH_SHORT).show()
                }
                clearSelection()
            }
        }.show(childFragmentManager, NoteEditorDialog.TAG)
    }

    private fun editAnnotation(entry: NovelAnnotationEntity) {
        NoteEditorDialog().apply {
            configure(
                existingNote = entry.note,
                excerpt = entry.excerpt,
                onDelete = { viewModel.deleteAnnotation(entry.annotationId) },
            ) { updatedNote ->
                viewModel.saveNote(
                    annotationId = entry.annotationId,
                    charStart = entry.charStart,
                    charEnd = entry.charEnd,
                    excerpt = entry.excerpt,
                    note = updatedNote,
                    colorArgb = entry.color,
                )
            }
        }.show(childFragmentManager, NoteEditorDialog.TAG)
    }

    private fun showReaderOverflowMenu() {
        val items = arrayOf(
            "笔记 / 高亮",
            "位置书签",
            "保存当前位置为书签",
            "导出",
            "阅读统计（Phase 3）",
            "金句卡（Phase 4）",
        )
        AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAnnotationsSheet()
                    1 -> showBookmarksSheet()
                    2 -> addBookmarkHere()
                    3 -> showExportSheet()
                    else -> Toast.makeText(requireContext(), "敬请期待", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showExportSheet() {
        val loaded = viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded
        if (loaded == null) {
            Toast.makeText(requireContext(), "小说还没加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        ExportSheet().apply {
            configure { format -> exportNovel(format, loaded) }
        }.show(childFragmentManager, ExportSheet.TAG)
    }

    private fun exportNovel(format: ExportFormat, loaded: NovelReaderV3ViewModel.LoadState.Loaded) {
        val ctx = requireContext().applicationContext
        Toast.makeText(requireContext(), "开始导出 ${format.displayName}…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = NovelExportManager.export(
                context = ctx,
                format = format,
                novel = loaded.novel,
                webNovel = loaded.webNovel,
                tokens = loaded.tokens,
            )
            when (result) {
                is ExportResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        "已导出到 Downloads/ShaftNovels/${result.fileName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is ExportResult.Failure -> {
                    Timber.tag("NovelReaderV3").e(result.cause, "Export failed: ${result.message}")
                    Toast.makeText(requireContext(), "导出失败：${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAnnotationsSheet() {
        val list = viewModel.annotations.value.orEmpty()
        AnnotationsSheet().apply {
            configure(
                entries = list,
                onJumpTo = { entry ->
                    viewModel.jumpToCharIndex(entry.charStart)
                    val pages = viewModel.pagination.value?.pages ?: return@configure
                    val idx = pages.indexOfFirst { it.charEnd >= entry.charStart }.coerceAtLeast(0)
                    readerView.goToPage(idx, animate = false)
                },
                onEdit = { entry -> editAnnotation(entry) },
                onDelete = { entry -> viewModel.deleteAnnotation(entry.annotationId) },
            )
        }.show(childFragmentManager, AnnotationsSheet.TAG)
    }

    private fun showBookmarksSheet() {
        val list = viewModel.bookmarks.value.orEmpty()
        BookmarksSheet().apply {
            configure(
                entries = list,
                onJumpTo = { entry ->
                    viewModel.jumpToCharIndex(entry.charIndex)
                    readerView.goToPage(entry.pageIndex, animate = false)
                },
                onDelete = { entry -> viewModel.deleteBookmark(entry.bookmarkId) },
            )
        }.show(childFragmentManager, BookmarksSheet.TAG)
    }

    private fun addBookmarkHere() {
        val pages = viewModel.pagination.value?.pages ?: return
        val idx = readerView.currentPageIndex()
        val page = pages.getOrNull(idx) ?: return
        val webNovel = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.webNovel
        val source = webNovel?.text.orEmpty()
        val preview = source.substring(page.charStart.coerceIn(0, source.length), minOf(source.length, page.charStart + 80))
            .replace('\n', ' ').trim()
        viewModel.addPositionBookmark(page.charStart, idx, preview)
        Toast.makeText(requireContext(), "已保存位置书签", Toast.LENGTH_SHORT).show()
    }

    private fun searchHitRanges(): List<HighlightRange> = searchHits.mapIndexed { i, hit ->
        HighlightRange(
            absoluteStart = hit.absoluteStart,
            absoluteEnd = hit.absoluteEnd,
            color = 0x66FFEB3B.toInt(),
            isCurrent = i == currentHitIndex,
        )
    }

    private fun copySelection() {
        val sel = activeSelection ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("novel selection", sel.text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shareSelection() {
        val sel = activeSelection ?: return
        val novel = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.novel
        val author = novel?.user?.name.orEmpty()
        val title = novel?.title.orEmpty()
        val body = if (title.isEmpty()) sel.text else "「${sel.text}」\n\n—— $title${if (author.isNotEmpty()) " / $author" else ""}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "分享段落"))
    }

    private fun searchSelectionOnPixiv() {
        val sel = activeSelection ?: return
        val query = sel.text.trim()
        if (query.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pixiv.net/tags/${Uri.encode(query)}/novels"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show() }
    }

    private fun searchSelectionOnWeb() {
        val sel = activeSelection ?: return
        val query = sel.text.trim()
        if (query.isEmpty()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", query) }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(requireContext(), "没有找到可处理的应用", Toast.LENGTH_SHORT).show() }
    }

    private fun translateSelection() {
        val sel = activeSelection ?: return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, sel.text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, "翻译"))
        } else {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://translate.google.com/?sl=auto&tl=zh-CN&text=${Uri.encode(sel.text)}&op=translate"),
            )
            runCatching { startActivity(fallback) }
                .onFailure { Toast.makeText(requireContext(), "没有可用的翻译应用", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun wireSearchOverlay() {
        searchOverlay.onQueryChanged = { runSearch(it) }
        searchOverlay.onNext = { jumpToHit(1) }
        searchOverlay.onPrev = { jumpToHit(-1) }
        searchOverlay.onRegexToggle = { regex ->
            searchRegex = regex
            runSearch(searchOverlay.currentQuery())
        }
        searchOverlay.onClose = { closeSearch() }
    }

    private fun openSearch() {
        chrome.hide()
        searchOverlay.setShown(true)
    }

    private fun closeSearch() {
        searchOverlay.setShown(false)
        searchOverlay.clear()
        searchHits = emptyList()
        currentHitIndex = -1
        applySearchHighlights()
    }

    private fun runSearch(query: String) {
        if (query.isEmpty()) {
            searchHits = emptyList()
            currentHitIndex = -1
            searchOverlay.setCount(-1, 0)
            applySearchHighlights()
            return
        }
        val web = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.webNovel
            ?: return
        val source = web.text.orEmpty()
        val rawHits = SearchEngine.search(source, query, regex = searchRegex, caseSensitive = false)
        val pages = viewModel.pagination.value?.pages.orEmpty()
        searchHits = SearchEngine.annotatePageIndices(rawHits, pages)
        currentHitIndex = if (searchHits.isEmpty()) -1 else 0
        searchOverlay.setCount(currentHitIndex, searchHits.size)
        if (currentHitIndex >= 0) goToHit(currentHitIndex)
        applySearchHighlights()
    }

    private fun jumpToHit(delta: Int) {
        if (searchHits.isEmpty()) return
        val size = searchHits.size
        val newIndex = ((currentHitIndex + delta) % size + size) % size
        currentHitIndex = newIndex
        searchOverlay.setCount(currentHitIndex, size)
        goToHit(newIndex)
        applySearchHighlights()
    }

    private fun goToHit(index: Int) {
        val hit = searchHits.getOrNull(index) ?: return
        viewModel.jumpToCharIndex(hit.absoluteStart)
        val pages = viewModel.pagination.value?.pages ?: return
        val pageIdx = pages.indexOfFirst { it.charEnd >= hit.absoluteStart }.coerceAtLeast(0)
        readerView.goToPage(pageIdx, animate = false)
    }

    private fun applySearchHighlights() {
        rebuildOverlays()
    }

    private fun showChapterDrawer() {
        val tokens = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.tokens
            ?: return
        val outline = ContentParser.buildChapterOutline(tokens)
        if (outline.isEmpty()) {
            Toast.makeText(requireContext(), "这篇小说没有章节标记", Toast.LENGTH_SHORT).show()
            return
        }
        val currentStart = viewModel.pagination.value?.pages?.getOrNull(readerView.currentPageIndex())?.charStart ?: 0
        ChapterDrawerDialog().apply {
            configure(outline, currentStart) { entry ->
                viewModel.jumpToCharIndex(entry.sourceStart)
                val pages = viewModel.pagination.value?.pages ?: return@configure
                val idx = pages.indexOfFirst { it.charEnd >= entry.sourceStart }.coerceAtLeast(0)
                readerView.goToPage(idx, animate = false)
            }
        }.show(childFragmentManager, ChapterDrawerDialog.TAG)
    }

    private fun jumpChapter(forward: Boolean) {
        val tokens = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.tokens
            ?: return
        val outline = ContentParser.buildChapterOutline(tokens)
        if (outline.isEmpty()) {
            if (forward) readerView.flipForward() else readerView.flipBackward()
            return
        }
        val currentPage = viewModel.pagination.value?.pages?.getOrNull(readerView.currentPageIndex())
            ?: return
        val currentChar = currentPage.charStart
        val target: ChapterOutlineEntry? = if (forward) {
            outline.firstOrNull { it.sourceStart > currentChar }
        } else {
            outline.lastOrNull { it.sourceStart < currentChar } ?: outline.firstOrNull()
        }
        if (target != null) {
            viewModel.jumpToCharIndex(target.sourceStart)
            val pages = viewModel.pagination.value?.pages ?: return
            val idx = pages.indexOfFirst { it.charEnd >= target.sourceStart }.coerceAtLeast(0)
            readerView.goToPage(idx, animate = true)
        } else {
            Toast.makeText(requireContext(), if (forward) "已是最后一章" else "已是第一章", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pushStyleAndGeometryIfReady() {
        val ctx = context ?: return
        val w = readerView.width
        val h = readerView.height
        if (w <= 0 || h <= 0) return
        val snapshot = ReaderSettings.snapshot()
        val themeIsDark = currentThemeIsDark()
        if (snapshot == lastPushedSnapshot &&
            w == lastPushedWidth &&
            h == lastPushedHeight &&
            themeIsDark == lastPushedThemeIsDark
        ) {
            // No meaningful change — suppress spurious re-pagination that would
            // otherwise snap the reader back to startPageIndex mid-flip.
            return
        }
        Timber.tag("NovelReaderV3").d(
            "pushStyleAndGeometry: size=${w}x${h} font=${snapshot.fontSizeSp} line=${snapshot.lineSpacing} theme=${snapshot.themeId}",
        )
        lastPushedSnapshot = snapshot
        lastPushedWidth = w
        lastPushedHeight = h
        lastPushedThemeIsDark = themeIsDark
        val style = TypeStyle.from(ctx, snapshot, resolveActiveTheme())
        val density = resources.displayMetrics.density
        val horizontal = ReaderSettings.horizontalMarginDp * density
        val vertical = ReaderSettings.verticalMarginDp * density
        val geom = PageGeometry(
            width = w,
            height = h,
            paddingLeft = horizontal,
            paddingTop = vertical,
            paddingRight = horizontal,
            paddingBottom = vertical,
        )
        viewModel.updateLayout(style, geom)
    }

    private fun applyInteractionSettings() {
        readerView.setTouchLocked(ReaderSettings.touchLocked)
        rootView.keepScreenOn = ReaderSettings.keepScreenOn
    }

    private fun resolveActiveTheme(): ReaderTheme {
        return ReaderTheme.findPresetById(ReaderSettings.themeId) ?: ReaderTheme.WHITE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imageSource?.clear()
        imageSource = null
    }

    private fun resolveNovelId(): Long {
        arguments?.let { args ->
            val idLong = args.getLong(ARG_NOVEL_ID, 0L)
            if (idLong != 0L) return idLong
            val bean = args.getSerializable(Params.CONTENT) as? NovelBean
            if (bean != null) return bean.id.toLong()
        }
        return 0L
    }

    companion object {
        private const val ARG_NOVEL_ID = "novel_id"

        @JvmStatic
        fun newInstance(novelBean: NovelBean): NovelReaderV3Fragment {
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
    }
}
