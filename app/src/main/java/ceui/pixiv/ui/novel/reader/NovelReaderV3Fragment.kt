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
import ceui.pixiv.ui.detail.showV3Menu
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
import ceui.pixiv.ui.novel.reader.model.ReadingDirection
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver
import ceui.pixiv.ui.novel.reader.render.NovelReaderView
import ceui.pixiv.ui.novel.reader.render.NovelScrollReaderView
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
    private var scrollReaderView: NovelScrollReaderView? = null
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

        wireTopBar(tb)
        wireBottomBar(rv, bb, ch, so)
        wireReaderView(rv, ch)
        wireSearchOverlay(so)
        wireTextSelection(rv, ch)
        wireSystemBarInsets()
        wireBackPress(ch)

        observeReaderState(rv, tb, bb, so, ch)

        rv.setTouchLocked(ReaderSettings.touchLocked)
        rv.setTapZoneReversed(ReaderSettings.tapZoneReversed)
        binding.root.keepScreenOn = ReaderSettings.keepScreenOn

        if (ReaderSettings.readingDirection == ReadingDirection.Vertical) {
            rv.visibility = View.GONE
            ensureScrollReaderView(ch).visibility = View.VISIBLE
            // Data binds later: viewModel.load() → Loaded observer → rebindScrollViewIfActive()
        }
        updateScrollProgressBarVisibility()
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
        tb.onBookmarkLongClick = { openTagBookmarkForCurrentNovel() }
        tb.onMoreClick = { showTopMoreMenu() }
    }

    private fun wireBottomBar(rv: NovelReaderView, bb: ReaderBottomBar, chrome: ReaderChrome, so: ReaderSearchOverlay) {
        bb.onPrevChapter = { jumpChapter(forward = false) }
        bb.onNextChapter = { jumpChapter(forward = true) }
        bb.onChaptersClick = { showChapterDrawer() }
        bb.onSettingsClick = {
            ReaderSettingsPanel().show(childFragmentManager, ReaderSettingsPanel.TAG)
        }
        bb.onThemeToggleClick = {
            val isDark = currentThemeIsDark()
            ReaderSettings.themeId = if (isDark) ReaderTheme.KRAFT.id else ReaderTheme.NIGHT.id
            bb.setDarkMode(!isDark)
        }
        bb.onSearchClick = { chrome.hide(); so.setShown(true) }
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
                }
                ReaderSettings.ChangeEvent.Flip -> applyFlipMode(rv, ch)
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
                rebindScrollViewIfActive()
            }
        }

        viewModel.pagination.observe(viewLifecycleOwner) { pag ->
            if (pag == null) return@observe
            if (ReaderSettings.readingDirection == ReadingDirection.Vertical) return@observe
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
            ensureScrollReaderView(chrome).visibility = View.VISIBLE
            rebindScrollViewIfActive()
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
        updateScrollProgressBarVisibility()
    }

    private fun updateScrollProgressBarVisibility() {
        val show = ReaderSettings.readingDirection == ReadingDirection.Vertical
        binding.scrollProgressBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) binding.scrollProgressBar.scaleX = 0f
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
            sv.onCharIndexChanged = { charIndex -> viewModel.onScrollPositionChanged(charIndex) }
            sv.onScrollProgressChanged = { progress ->
                binding.scrollProgressBar.scaleX = progress
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
        sv.bind(loaded.tokens, style, geom, ImageResolver.of(loaded.webNovel))
        if (currentChar > 0) sv.jumpToCharIndex(currentChar)
    }

    private fun togglePixivBookmark() {
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), viewModel.toggleBookmark(), Toast.LENGTH_SHORT).show()
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

    private fun showTopMoreMenu() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        viewLifecycleOwner.lifecycleScope.launch {
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: runCatching { Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) } }
                    .getOrNull()
            if (novel == null) {
                Toast.makeText(requireContext(), getString(R.string.msg_novel_loading), Toast.LENGTH_SHORT).show()
                return@launch
            }
            showV3Menu {
                item(getString(R.string.view_comments), R.drawable.ic_baseline_comment_24) {
                    val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                        putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                        putExtra(Params.NOVEL_ID, novelId.toInt())
                    }
                    startActivity(intent)
                }
                item(getString(R.string.string_110), R.drawable.ic_share_black_24dp) {
                    shareNovel(novel)
                }
                item(getString(R.string.menu_copy_link), R.drawable.ic_baseline_launch_24) {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pixiv-novel", NOVEL_URL_HEAD + novelId))
                    Toast.makeText(requireContext(), getString(R.string.msg_link_copied), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showReaderOverflowMenu() {
        showV3Menu {
            item(getString(R.string.menu_annotations), R.drawable.ic_reader_annotations) {
                showAnnotationsSheet()
            }
            item(getString(R.string.menu_bookmarks), R.drawable.ic_baseline_bookmark_24) {
                showBookmarksSheet()
            }
            item(getString(R.string.menu_save_position), R.drawable.ic_baseline_bookmark_24) {
                viewModel.addBookmarkAtCurrentPage(readerView?.currentPageIndex() ?: 0)
                Toast.makeText(requireContext(), getString(R.string.msg_bookmark_saved), Toast.LENGTH_SHORT).show()
            }
            item(getString(R.string.menu_export), R.drawable.ic_baseline_get_app_24) {
                showExportSheet()
            }
        }
    }

    private fun showExportSheet() {
        if (viewModel.loadState.value !is NovelReaderV3ViewModel.LoadState.Loaded) {
            Toast.makeText(requireContext(), getString(R.string.msg_novel_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        ExportSheet().apply {
            configure { format ->
                Toast.makeText(requireContext(), getString(R.string.msg_export_start, format.displayName), Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val result = viewModel.exportNovel(format)) {
                        is ExportResult.Success -> Toast.makeText(requireContext(), getString(R.string.msg_export_success, result.fileName), Toast.LENGTH_LONG).show()
                        is ExportResult.Failure -> Toast.makeText(requireContext(), getString(R.string.msg_export_fail, result.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.show(childFragmentManager, ExportSheet.TAG)
    }

    private fun pickHighlightColor() {
        val sel = activeSelection ?: return
        val options = listOf(getString(R.string.highlight_yellow) to HighlightColor.Yellow, getString(R.string.highlight_green) to HighlightColor.Green, getString(R.string.highlight_pink) to HighlightColor.Pink, getString(R.string.highlight_blue) to HighlightColor.Blue)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_choose_highlight_color))
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, options[which].second.argb)
                Toast.makeText(requireContext(), getString(R.string.msg_highlighted), Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun openNoteEditorForSelection() {
        val sel = activeSelection ?: return
        NoteEditorDialog().apply {
            configure(existingNote = "", excerpt = sel.text) { noteText ->
                if (noteText.isNotEmpty()) {
                    viewModel.saveNote(0L, sel.absoluteStart, sel.absoluteEnd, sel.text, noteText, HighlightColor.Yellow.argb)
                    Toast.makeText(requireContext(), getString(R.string.msg_note_saved), Toast.LENGTH_SHORT).show()
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
                onJumpTo = { entry -> navigateToCharIndex(entry.charStart) },
                onEdit = { editAnnotation(it) },
                onDelete = { viewModel.deleteAnnotation(it.annotationId) },
            )
        }.show(childFragmentManager, AnnotationsSheet.TAG)
    }

    private fun showBookmarksSheet() {
        BookmarksSheet().apply {
            configure(
                entries = viewModel.bookmarks.value.orEmpty(),
                onJumpTo = { entry -> navigateToCharIndex(entry.charIndex) },
                onDelete = { viewModel.deleteBookmark(it.bookmarkId) },
            )
        }.show(childFragmentManager, BookmarksSheet.TAG)
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
            Toast.makeText(requireContext(), getString(R.string.msg_no_chapters), Toast.LENGTH_SHORT).show()
            return
        }
        val currentStart = scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.currentCharIndex()
            ?: viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart
            ?: 0
        ChapterListSheet().apply {
            configure(outline, currentStart) { entry -> navigateToCharIndex(entry.sourceStart) }
        }.show(childFragmentManager, ChapterListSheet.TAG)
    }

    private fun jumpChapter(forward: Boolean) {
        val outline = viewModel.getChapterOutline()
        if (outline.isEmpty()) {
            if (forward) readerView?.flipForward() else readerView?.flipBackward()
            return
        }
        val currentChar = scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.currentCharIndex()
            ?: viewModel.pagination.value?.pages?.getOrNull(readerView?.currentPageIndex() ?: 0)?.charStart
            ?: return
        val target = if (forward) outline.firstOrNull { it.sourceStart > currentChar } else (outline.lastOrNull { it.sourceStart < currentChar } ?: outline.firstOrNull())
        if (target != null) {
            navigateToCharIndex(target.sourceStart, animate = true)
        } else {
            Toast.makeText(requireContext(), if (forward) getString(R.string.msg_last_chapter) else getString(R.string.msg_first_chapter), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Selection actions --------------------------------------------------

    private fun copySelection() {
        val sel = activeSelection ?: return
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("novel selection", sel.text))
        Toast.makeText(requireContext(), getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
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
            .onFailure { Toast.makeText(requireContext(), getString(R.string.msg_no_browser), Toast.LENGTH_SHORT).show() }
    }

    private fun searchSelectionOnWeb() {
        val query = activeSelection?.text?.trim().orEmpty()
        if (query.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", query) }) }
            .onFailure { Toast.makeText(requireContext(), getString(R.string.msg_no_app), Toast.LENGTH_SHORT).show() }
    }

    private fun translateSelection() {
        val sel = activeSelection ?: return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain"; putExtra(Intent.EXTRA_PROCESS_TEXT, sel.text); putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, getString(R.string.chooser_translate)))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=zh-CN&text=${Uri.encode(sel.text)}&op=translate"))) }
                .onFailure { Toast.makeText(requireContext(), getString(R.string.msg_no_translate_app), Toast.LENGTH_SHORT).show() }
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
        scrollReaderView = null
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
        private const val COLOR_SEARCH_CURRENT = 0xAAFF9800.toInt() // opaque orange
        private const val COLOR_SEARCH_OTHER = 0x66FFEB3B           // semi-transparent yellow

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
