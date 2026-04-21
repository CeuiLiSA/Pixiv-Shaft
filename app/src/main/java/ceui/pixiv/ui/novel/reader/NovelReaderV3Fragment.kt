package ceui.pixiv.ui.novel.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.databinding.FragmentNovelReaderV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import java.util.UUID
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.model.HighlightColor
import ceui.pixiv.ui.novel.reader.model.HighlightSpan
import ceui.pixiv.ui.novel.reader.model.SearchHit
import ceui.pixiv.ui.novel.reader.model.TextSelection
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.GlideImageBitmapSource
import ceui.pixiv.ui.novel.reader.render.HighlightRange
import ceui.pixiv.ui.novel.reader.render.NovelReaderView
import ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView
import ceui.pixiv.ui.novel.reader.render.PageOverlays
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import ceui.pixiv.ui.novel.reader.ui.AnnotationsSheet
import ceui.pixiv.ui.novel.reader.ui.BookmarksSheet
import ceui.pixiv.ui.novel.reader.ui.ChapterListSheet
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.novel.reader.ui.NoteEditorDialog
import ceui.pixiv.ui.novel.reader.ui.ReaderBottomBar
import ceui.pixiv.ui.novel.reader.ui.ReaderChrome
import ceui.pixiv.ui.novel.reader.ui.ReaderSearchOverlay
import ceui.pixiv.ui.novel.reader.ui.ReaderSettingsPanel
import ceui.pixiv.ui.novel.reader.ui.ReaderTopBar
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelReaderV3Fragment : Fragment(R.layout.fragment_novel_reader_v3) {

    private val binding by viewBinding(FragmentNovelReaderV3Binding::bind)
    private val viewModel: NovelReaderV3ViewModel by viewModels {
        NovelReaderV3ViewModel.factory(resolveNovelId())
    }

    private var readerView: NovelReaderView? = null
    private var topBar: ReaderTopBar? = null
    private var bottomBar: ReaderBottomBar? = null
    private var chrome: ReaderChrome? = null
    private var searchOverlay: ReaderSearchOverlay? = null
    private var imageSource: GlideImageBitmapSource? = null

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
        val ch = ReaderChrome(tb, bb)
        val so = ReaderSearchOverlay(binding.readerSearchOverlay)
        topBar = tb
        bottomBar = bb
        chrome = ch
        searchOverlay = so

        wireTopBar(tb)
        wireBottomBar(rv, bb, ch)
        wireReaderView(rv, ch)
        wireSearchOverlay(so)
        wireTextSelection(rv, ch)
        wireSystemBarInsets()
        wireBackPress(ch)

        observeReaderState(rv, tb, bb, so)

        rv.setTouchLocked(ReaderSettings.touchLocked)
        rv.setTapZoneReversed(ReaderSettings.tapZoneReversed)
        binding.root.keepScreenOn = ReaderSettings.keepScreenOn
        viewModel.load()
    }

    // ---- Wiring -------------------------------------------------------------

    private fun wireBackPress(chrome: ReaderChrome) {
        val cb = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chrome.isShown) {
                    chrome.hide()
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
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
        tb.onBookmarkClick = { togglePixivBookmark() }
        tb.onMoreClick = { showTopMoreMenu() }
    }

    private fun wireBottomBar(rv: NovelReaderView, bb: ReaderBottomBar, chrome: ReaderChrome) {
        bb.onPrevChapter = { jumpChapter(forward = false) }
        bb.onNextChapter = { jumpChapter(forward = true) }
        bb.onChaptersClick = { showChapterDrawer() }
        bb.onSettingsClick = {
            ReaderSettingsPanel().show(childFragmentManager, ReaderSettingsPanel.TAG)
        }
        bb.onThemeToggleClick = { toggleDayNightTheme() }
        bb.onSearchClick = {
            chrome.hide()
            searchOverlay?.setShown(true)
        }
        bb.onMoreClick = { showReaderOverflowMenu() }
        bb.onSeekCommit = { pageIndex -> rv.goToPage(pageIndex, animate = false) }
    }

    private fun wireSearchOverlay(so: ReaderSearchOverlay) {
        so.onQueryChanged = { runSearch(it) }
        so.onNext = { jumpToHit(1) }
        so.onPrev = { jumpToHit(-1) }
        so.onRegexToggle = { regex ->
            searchRegex = regex
            runSearch(so.currentQuery())
        }
        so.onClose = {
            so.setShown(false)
            so.clear()
            viewModel.clearSearch()
        }
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
                ReaderTextBlockView.MenuEntry(idCopy, "复制"),
                ReaderTextBlockView.MenuEntry(ReaderTextBlockView.ID_SELECT_ALL, "全选"),
                ReaderTextBlockView.MenuEntry(idHighlightParent, "标记高亮"),
                ReaderTextBlockView.MenuEntry(idNote, "笔记"),
                ReaderTextBlockView.MenuEntry(idTranslate, "翻译"),
                ReaderTextBlockView.MenuEntry(idSearchPixiv, "P站"),
                ReaderTextBlockView.MenuEntry(idSearchWeb, "网页"),
                ReaderTextBlockView.MenuEntry(idShare, "分享"),
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

    // ---- Observe ------------------------------------------------------------

    private fun observeReaderState(
        rv: NovelReaderView,
        tb: ReaderTopBar,
        bb: ReaderBottomBar,
        so: ReaderSearchOverlay,
    ) {
        ReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            when (event) {
                ReaderSettings.ChangeEvent.Layout,
                ReaderSettings.ChangeEvent.Theme,
                -> pushStyleAndGeometryIfReady()
                ReaderSettings.ChangeEvent.Flip -> rv.setFlipMode(ReaderSettings.flipMode)
                ReaderSettings.ChangeEvent.Interaction -> {
                    rv.setTouchLocked(ReaderSettings.touchLocked)
                    rv.setTapZoneReversed(ReaderSettings.tapZoneReversed)
                    binding.root.keepScreenOn = ReaderSettings.keepScreenOn
                }
                else -> Unit
            }
        }

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            binding.readerLoading.visibility = if (state is NovelReaderV3ViewModel.LoadState.Loading) View.VISIBLE else View.GONE
            binding.readerError.visibility = if (state is NovelReaderV3ViewModel.LoadState.Error) View.VISIBLE else View.GONE
            if (state is NovelReaderV3ViewModel.LoadState.Error) binding.readerError.text = state.message
            if (state is NovelReaderV3ViewModel.LoadState.Loaded) {
                tb.setTitle(state.novel?.title ?: state.webNovel.title.orEmpty())
                pushStyleAndGeometryIfReady()
            }
        }

        viewModel.pagination.observe(viewLifecycleOwner) { pag ->
            if (pag == null) return@observe
            rv.setStyle(pag.style, pag.geometry)
            rv.bind(pag.pages, pag.startPageIndex)
            rv.setFlipMode(ReaderSettings.flipMode)
            bb.setProgress(pag.startPageIndex, pag.pages.size)
        }

        viewModel.currentPageIndex.observe(viewLifecycleOwner) { index ->
            bb.setProgress(index, viewModel.pagination.value?.pages?.size ?: 0)
        }

        ObjectPool.get<Novel>(resolveNovelId()).observe(viewLifecycleOwner) { novel ->
            tb.setBookmarked(novel?.is_bookmarked == true)
            val title = novel?.title
            if (!title.isNullOrEmpty()) tb.setTitle(title)
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
        readerView?.setOverlays(
            PageOverlays(
                searchHits = searchHitRanges(),
                annotations = annotationSpans,
                selection = activeSelection,
            ),
        )
    }

    private fun clearSelection() {
        activeSelection = null
        rebuildOverlays()
    }

    private fun togglePixivBookmark() {
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), viewModel.toggleBookmark(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDayNightTheme() {
        val isDark = currentThemeIsDark()
        ReaderSettings.themeId = if (isDark) ReaderTheme.KRAFT.id else ReaderTheme.NIGHT.id
        bottomBar?.setDarkMode(!isDark)
    }

    private fun currentThemeIsDark(): Boolean =
        ReaderTheme.findPresetById(ReaderSettings.themeId)?.isDark == true

    private fun showTopMoreMenu() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        viewLifecycleOwner.lifecycleScope.launch {
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: runCatching { Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) } }
                    .getOrNull()
            if (novel == null) {
                Toast.makeText(requireContext(), "小说信息还没加载，请稍后再试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showActionMenu {
                add(MenuItem(getString(R.string.view_comments)) {
                    val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                        putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                        putExtra(Params.NOVEL_ID, novelId.toInt())
                    }
                    startActivity(intent)
                })
                add(MenuItem(getString(R.string.string_110)) { shareNovel(novel) })
                add(MenuItem("复制链接") {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pixiv-novel", NOVEL_URL_HEAD + novelId))
                    Toast.makeText(requireContext(), "链接已复制", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    private fun showReaderOverflowMenu() {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("笔记 / 高亮", "位置书签", "保存当前位置为书签", "导出", "阅读统计（Phase 3）", "金句卡（Phase 4）")) { _, which ->
                when (which) {
                    0 -> showAnnotationsSheet()
                    1 -> showBookmarksSheet()
                    2 -> {
                        viewModel.addBookmarkAtCurrentPage(readerView?.currentPageIndex() ?: 0)
                        Toast.makeText(requireContext(), "已保存位置书签", Toast.LENGTH_SHORT).show()
                    }
                    3 -> showExportSheet()
                    else -> Toast.makeText(requireContext(), "敬请期待", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showExportSheet() {
        if (viewModel.loadState.value !is NovelReaderV3ViewModel.LoadState.Loaded) {
            Toast.makeText(requireContext(), "小说还没加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        ExportSheet().apply {
            configure { format ->
                Toast.makeText(requireContext(), "开始导出 ${format.displayName}…", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val result = viewModel.exportNovel(format)) {
                        is ExportResult.Success -> Toast.makeText(requireContext(), "已导出到 Downloads/ShaftNovels/${result.fileName}", Toast.LENGTH_LONG).show()
                        is ExportResult.Failure -> Toast.makeText(requireContext(), "导出失败：${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.show(childFragmentManager, ExportSheet.TAG)
    }

    private fun pickHighlightColor() {
        val sel = activeSelection ?: return
        val options = listOf("黄色" to HighlightColor.Yellow, "绿色" to HighlightColor.Green, "粉色" to HighlightColor.Pink, "蓝色" to HighlightColor.Blue)
        AlertDialog.Builder(requireContext())
            .setTitle("选择高亮颜色")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, options[which].second.argb)
                Toast.makeText(requireContext(), "已高亮", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openNoteEditorForSelection() {
        val sel = activeSelection ?: return
        NoteEditorDialog().apply {
            configure(existingNote = "", excerpt = sel.text) { noteText ->
                if (noteText.isNotEmpty()) {
                    viewModel.saveNote(0L, sel.absoluteStart, sel.absoluteEnd, sel.text, noteText, HighlightColor.Yellow.argb)
                    Toast.makeText(requireContext(), "笔记已保存", Toast.LENGTH_SHORT).show()
                }
                clearSelection()
            }
        }.show(childFragmentManager, NoteEditorDialog.TAG)
    }

    private fun editAnnotation(entry: NovelAnnotationEntity) {
        NoteEditorDialog().apply {
            configure(existingNote = entry.note, excerpt = entry.excerpt, onDelete = { viewModel.deleteAnnotation(entry.annotationId) }) { updatedNote ->
                viewModel.saveNote(entry.annotationId, entry.charStart, entry.charEnd, entry.excerpt, updatedNote, entry.color)
            }
        }.show(childFragmentManager, NoteEditorDialog.TAG)
    }

    private fun showAnnotationsSheet() {
        AnnotationsSheet().apply {
            configure(
                entries = viewModel.annotations.value.orEmpty(),
                onJumpTo = { entry ->
                    viewModel.jumpToCharIndex(entry.charStart)
                    val idx = viewModel.pagination.value?.pages?.indexOfFirst { it.charEnd >= entry.charStart }?.coerceAtLeast(0) ?: 0
                    readerView?.goToPage(idx, animate = false)
                },
                onEdit = { editAnnotation(it) },
                onDelete = { viewModel.deleteAnnotation(it.annotationId) },
            )
        }.show(childFragmentManager, AnnotationsSheet.TAG)
    }

    private fun showBookmarksSheet() {
        BookmarksSheet().apply {
            configure(
                entries = viewModel.bookmarks.value.orEmpty(),
                onJumpTo = { entry ->
                    viewModel.jumpToCharIndex(entry.charIndex)
                    readerView?.goToPage(entry.pageIndex, animate = false)
                },
                onDelete = { viewModel.deleteBookmark(it.bookmarkId) },
            )
        }.show(childFragmentManager, BookmarksSheet.TAG)
    }

    // ---- Search -------------------------------------------------------------

    private fun searchHitRanges(): List<HighlightRange> {
        val result = viewModel.searchResult.value ?: return emptyList()
        return result.hits.mapIndexed { i, hit ->
            HighlightRange(hit.absoluteStart, hit.absoluteEnd, 0x66FFEB3B.toInt(), isCurrent = i == result.currentIndex)
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
        viewModel.jumpToCharIndex(hit.absoluteStart)
        val pageIdx = viewModel.pagination.value?.pages?.indexOfFirst { it.charEnd >= hit.absoluteStart }?.coerceAtLeast(0) ?: 0
        readerView?.goToPage(pageIdx, animate = false)
    }

    // ---- Chapter navigation -------------------------------------------------

    private fun showChapterDrawer() {
        val outline = viewModel.getChapterOutline()
        if (outline.isEmpty()) {
            Toast.makeText(requireContext(), "这篇小说没有章节标记", Toast.LENGTH_SHORT).show()
            return
        }
        val currentStart = viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart ?: 0
        ChapterListSheet().apply {
            configure(outline, currentStart) { entry ->
                viewModel.jumpToCharIndex(entry.sourceStart)
                val idx = viewModel.pagination.value?.pages?.indexOfFirst { it.charEnd >= entry.sourceStart }?.coerceAtLeast(0) ?: 0
                readerView?.goToPage(idx, animate = false)
            }
        }.show(childFragmentManager, ChapterListSheet.TAG)
    }

    private fun jumpChapter(forward: Boolean) {
        val outline = viewModel.getChapterOutline()
        if (outline.isEmpty()) {
            if (forward) readerView?.flipForward() else readerView?.flipBackward()
            return
        }
        val currentChar = viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart ?: return
        val target = if (forward) outline.firstOrNull { it.sourceStart > currentChar } else (outline.lastOrNull { it.sourceStart < currentChar } ?: outline.firstOrNull())
        if (target != null) {
            viewModel.jumpToCharIndex(target.sourceStart)
            val idx = viewModel.pagination.value?.pages?.indexOfFirst { it.charEnd >= target.sourceStart }?.coerceAtLeast(0) ?: 0
            readerView?.goToPage(idx, animate = true)
        } else {
            Toast.makeText(requireContext(), if (forward) "已是最后一章" else "已是第一章", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Selection actions --------------------------------------------------

    private fun copySelection() {
        val sel = activeSelection ?: return
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("novel selection", sel.text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shareSelection() {
        val sel = activeSelection ?: return
        val novel = (viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded)?.novel
        val author = novel?.user?.name.orEmpty()
        val title = novel?.title.orEmpty()
        val body = if (title.isEmpty()) sel.text else "「${sel.text}」\n\n—— $title${if (author.isNotEmpty()) " / $author" else ""}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, "分享段落"))
    }

    private fun searchSelectionOnPixiv() {
        val query = activeSelection?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pixiv.net/tags/${Uri.encode(query)}/novels"))) }
            .onFailure { Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show() }
    }

    private fun searchSelectionOnWeb() {
        val query = activeSelection?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", query) }) }
            .onFailure { Toast.makeText(requireContext(), "没有找到可处理的应用", Toast.LENGTH_SHORT).show() }
    }

    private fun translateSelection() {
        val sel = activeSelection ?: return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain"; putExtra(Intent.EXTRA_PROCESS_TEXT, sel.text); putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, "翻译"))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=zh-CN&text=${Uri.encode(sel.text)}&op=translate"))) }
                .onFailure { Toast.makeText(requireContext(), "没有可用的翻译应用", Toast.LENGTH_SHORT).show() }
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
                            val bean = Shaft.sGson.let { g -> g.fromJson(g.toJson(illust), IllustsBean::class.java) }
                            val uuid = UUID.randomUUID().toString()
                            Container.get().addPageToMap(PageData(uuid, null, listOf(bean)))
                            startActivity(Intent(requireContext(), VActivity::class.java).apply {
                                putExtra(Params.POSITION, 0); putExtra(Params.PAGE_UUID, uuid)
                            })
                        }
                }
            }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onDestroyView() {
        super.onDestroyView()
        imageSource?.clear()
        imageSource = null
        readerView = null
        topBar = null
        bottomBar = null
        chrome = null
        searchOverlay = null
    }

    private fun resolveNovelId(): Long {
        arguments?.let { args ->
            val idLong = args.getLong(ARG_NOVEL_ID, 0L)
            if (idLong != 0L) return idLong
            @Suppress("DEPRECATION")
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
