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
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.pushFragment
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.shareNovel
import java.util.UUID
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.feature.SearchEngine
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
    private var lastPushedTopInset: Int = 0
    private var lastPushedBottomInset: Int = 0

    // System bar / cutout insets — TemplateActivity runs edge-to-edge so the
    // reader view extends behind the status bar and gesture nav. We add these
    // to the page padding so text never lives under the system chrome.
    private var topInsetPx: Int = 0
    private var bottomInsetPx: Int = 0

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

        wireTopBar()
        wireBottomBar()
        wireReaderView()
        wireSearchOverlay()
        wireTextSelection()
        wireSystemBarInsets()
        wireBackPress()

        observeReaderState()

        applyInteractionSettings()
        viewModel.load()
    }

    private fun wireBackPress() {
        val cb = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chrome.isShown) {
                    chrome.hide()
                    return
                }
                // Bars already hidden — yield to the system so the activity /
                // host navigator can finish naturally.
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
    }

    private fun wireSystemBarInsets() {
        val topBarView = rootView.findViewById<View>(R.id.reader_top_bar)
        val bottomBarView = rootView.findViewById<View>(R.id.reader_bottom_bar)
        val searchOverlayView = rootView.findViewById<View>(R.id.reader_search_overlay)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // Search overlay sits directly under the status bar (there's no top
            // bar above it when it's shown alone), so add an extra breathing
            // room beyond the raw inset to keep the EditText / icons from
            // hugging the clock / battery strip.
            val extraTop = (8 * resources.displayMetrics.density).toInt()
            topBarView.updatePadding(top = bars.top)
            searchOverlayView.updatePadding(top = bars.top + extraTop)
            bottomBarView.updatePadding(bottom = bars.bottom)
            if (bars.top != topInsetPx || bars.bottom != bottomInsetPx) {
                topInsetPx = bars.top
                bottomInsetPx = bars.bottom
                pushStyleAndGeometryIfReady()
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun wireReaderView() {
        readerView.onTapCenter = {
            if (activeSelection != null) clearSelection() else chrome.toggle()
        }
        readerView.onImageTap = { image -> openImageElement(image) }
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
        topBar.onAnnotationsClick = { showAnnotationsSheet() }
        topBar.onBookmarkClick = { togglePixivBookmark() }
        topBar.onMoreClick = { showTopMoreMenu() }
    }

    private fun showTopMoreMenu() {
        val novelId = resolveNovelId()
        if (novelId == 0L) return
        // Prefer the cached Novel (drives bookmark count, author, etc); if it
        // isn't loaded yet, fetch on demand so the menu still works at startup.
        viewLifecycleOwner.lifecycleScope.launch {
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: runCatching { Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) } }
                    .getOrNull()
            if (novel == null) {
                Toast.makeText(requireContext(), "小说信息还没加载，请稍后再试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showActionMenu {
                add(
                    MenuItem(getString(R.string.view_comments)) {
                        val intent = android.content.Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                            putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                            putExtra(Params.NOVEL_ID, novelId.toInt())
                        }
                        startActivity(intent)
                    },
                )
                add(
                    MenuItem(getString(R.string.string_110)) {
                        shareNovel(novel)
                    },
                )
                add(
                    MenuItem("复制链接") {
                        copyNovelLink(novelId)
                    },
                )
            }
        }
    }

    private fun copyNovelLink(novelId: Long) {
        val link = NOVEL_URL_HEAD + novelId
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pixiv-novel", link))
        Toast.makeText(requireContext(), "链接已复制", Toast.LENGTH_SHORT).show()
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
        ReaderSettings.themeId = if (isDark) ReaderTheme.KRAFT.id else ReaderTheme.NIGHT.id
        bottomBar.setDarkMode(!isDark)
        Timber.tag("NovelReaderV3").d("Theme toggled: $current -> ${ReaderSettings.themeId}")
    }

    private fun currentThemeIsDark(): Boolean {
        return ReaderTheme.findPresetById(ReaderSettings.themeId)?.isDark == true
    }

    private fun wireTextSelection() {
        // Action-mode menu ids. Kept stable so [ReaderTextBlockView.onMenuAction]
        // dispatches correctly.
        val idCopy = 1
        val idShare = 2
        val idSearchPixiv = 3
        val idSearchWeb = 4
        val idTranslate = 5
        val idHighlightParent = 9
        val idHighlightYellow = 10
        val idHighlightGreen = 11
        val idHighlightPink = 12
        val idHighlightBlue = 13
        val idNote = 20

        val menuEntries = listOf(
            ReaderTextBlockView.MenuEntry(idCopy, "复制"),
            ReaderTextBlockView.MenuEntry(ReaderTextBlockView.ID_SELECT_ALL, "全选"),
            ReaderTextBlockView.MenuEntry(idHighlightParent, "标记高亮"),
            ReaderTextBlockView.MenuEntry(idNote, "笔记"),
            ReaderTextBlockView.MenuEntry(idTranslate, "翻译"),
            ReaderTextBlockView.MenuEntry(idSearchPixiv, "P站"),
            ReaderTextBlockView.MenuEntry(idSearchWeb, "网页"),
            ReaderTextBlockView.MenuEntry(idShare, "分享"),
        )

        readerView.setTextBlockSelectionHandlers(
            onStart = { _, absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text.toString())
                chrome.hide()
            },
            onChange = { _, absStart, absEnd, text ->
                activeSelection = TextSelection(absStart, absEnd, text.toString())
            },
            onEnd = {
                activeSelection = null
            },
            menuEntries = menuEntries,
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

    private fun clearSelection() {
        activeSelection = null
        rebuildOverlays()
    }

    private fun saveHighlight(color: HighlightColor) {
        val sel = activeSelection ?: return
        viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, color.argb)
        Toast.makeText(requireContext(), "已高亮", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun pickHighlightColor() {
        // Snapshot selection before the ActionMode is finished — by the time
        // the dialog's item-click fires, `activeSelection` has already been
        // nulled out by onSelectionEnded.
        val sel = activeSelection ?: return
        val options = listOf(
            "黄色" to HighlightColor.Yellow,
            "绿色" to HighlightColor.Green,
            "粉色" to HighlightColor.Pink,
            "蓝色" to HighlightColor.Blue,
        )
        AlertDialog.Builder(requireContext())
            .setTitle("选择高亮颜色")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val color = options[which].second
                viewModel.addHighlight(sel.absoluteStart, sel.absoluteEnd, sel.text, color.argb)
                Toast.makeText(requireContext(), "已高亮", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
            .setNegativeButton("取消", null)
            .show()
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
        ChapterListSheet().apply {
            configure(outline, currentStart) { entry ->
                viewModel.jumpToCharIndex(entry.sourceStart)
                val pages = viewModel.pagination.value?.pages ?: return@configure
                val idx = pages.indexOfFirst { it.charEnd >= entry.sourceStart }.coerceAtLeast(0)
                readerView.goToPage(idx, animate = false)
            }
        }.show(childFragmentManager, ChapterListSheet.TAG)
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
            themeIsDark == lastPushedThemeIsDark &&
            topInsetPx == lastPushedTopInset &&
            bottomInsetPx == lastPushedBottomInset
        ) {
            // No meaningful change — suppress spurious re-pagination that would
            // otherwise snap the reader back to startPageIndex mid-flip.
            return
        }
        Timber.tag("NovelReaderV3").d(
            "pushStyleAndGeometry: size=${w}x${h} font=${snapshot.fontSizeSp} line=${snapshot.lineSpacing} theme=${snapshot.themeId} insets=top:${topInsetPx}/bot:${bottomInsetPx}",
        )
        lastPushedSnapshot = snapshot
        lastPushedWidth = w
        lastPushedHeight = h
        lastPushedThemeIsDark = themeIsDark
        lastPushedTopInset = topInsetPx
        lastPushedBottomInset = bottomInsetPx
        val style = TypeStyle.from(ctx, snapshot, resolveActiveTheme())
        val density = resources.displayMetrics.density
        val horizontal = ReaderSettings.horizontalMarginDp * density
        // Vertical margin used to stack *on top of* the system-bar inset, which
        // left a visibly huge gap above the first line (inset + 24dp reading
        // margin) whenever the top bar was toggled on. Fold the reading margin
        // into the inset so the first line sits a small, fixed distance below
        // whatever the system reserved, never piling up.
        val verticalMargin = ReaderSettings.verticalMarginDp * density
        val geom = PageGeometry(
            width = w,
            height = h,
            paddingLeft = horizontal,
            paddingTop = maxOf(topInsetPx.toFloat(), verticalMargin),
            paddingRight = horizontal,
            paddingBottom = maxOf(bottomInsetPx.toFloat(), verticalMargin),
        )
        viewModel.updateLayout(style, geom)
    }

    private fun applyInteractionSettings() {
        readerView.setTouchLocked(ReaderSettings.touchLocked)
        rootView.keepScreenOn = ReaderSettings.keepScreenOn
    }

    private fun resolveActiveTheme(): ReaderTheme {
        return ReaderTheme.findPresetById(ReaderSettings.themeId) ?: ReaderTheme.KRAFT
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imageSource?.clear()
        imageSource = null
    }

    private fun openImageElement(image: ceui.pixiv.ui.novel.reader.model.PageElement.Image) {
        when (image.imageType) {
            ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.UploadedImage -> {
                val url = image.imageUrl ?: return
                val novelId = resolveNovelId()
                ImageUrlViewer.open(
                    requireContext(),
                    url,
                    saveName = "novel_${novelId}_upload_${image.resourceId}",
                )
            }
            ceui.pixiv.ui.novel.reader.model.PageElement.Image.ImageType.PixivImage -> {
                val illustId = image.resourceId
                if (illustId <= 0L) return
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { Client.appApi.getIllust(illustId).illust }
                        .getOrNull()
                        ?.let { illust ->
                            val gson = Shaft.sGson
                            val bean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
                            val uuid = UUID.randomUUID().toString()
                            val pageData = PageData(uuid, null, listOf(bean))
                            Container.get().addPageToMap(pageData)
                            val intent = android.content.Intent(requireContext(), VActivity::class.java).apply {
                                putExtra(Params.POSITION, 0)
                                putExtra(Params.PAGE_UUID, uuid)
                            }
                            startActivity(intent)
                        }
                }
            }
        }
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
