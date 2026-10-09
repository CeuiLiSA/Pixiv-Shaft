package ceui.pixiv.ui.comic.reader

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentComicReaderV3Binding
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.imageloader.PageImageSourceResolver
import ceui.pixiv.imageloader.awaitFile
import ceui.pixiv.services.requireNetworkStateManager
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import com.hjq.toast.Toaster
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 漫画阅读器 V3 Fragment：MVVM-Lite + Bridge + Composition Root。
 *
 * 职责单一化：
 * - 纯渲染：观察 ViewModel state（loadState / currentPage / events）
 * - 纯派发：用户手势 → ViewModel intent（addBookmarkAt / stepPage / jumpSeriesNeighbor / ...）
 * - 不持有 Repository / UseCase / Tracker / Prefetcher —— 这些都在 ViewModel 里活，跨旋转可靠
 * - 仅持有 View 级别协调器（[ComicChrome] / [ComicWindowController] / [ComicViewport]）
 */
class ComicReaderV3Fragment : Fragment(R.layout.fragment_comic_reader_v3) {

    private val binding by viewBinding(FragmentComicReaderV3Binding::bind)
    private val viewModel: ComicReaderV3ViewModel by viewModels {
        ComicReaderV3ViewModel.factory(resolveIllustId())
    }
    private val eventBus by activityViewModels<ComicReaderEventBus>()
    private val pagesProvider by activityViewModels<ComicReaderPagesProvider>()

    private lateinit var chrome: ComicChrome
    private lateinit var windowController: ComicWindowController
    private lateinit var pagedViewport: PagedViewport
    private lateinit var webtoonViewport: WebtoonViewport
    private lateinit var current: ComicViewport

    private lateinit var retryController: PageLoadRetryController
    private var orientationJob: Job? = null
    private var chromeAutoHideJob: Job? = null

    /** 打开阅读器后操作栏只自动收起这一次(#1172)；操作栏被收起过、或用户碰过它，就不再自动收。 */
    private var chromeAutoHidePending = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chrome = ComicChrome(binding.comicTopBar.root, binding.comicBottomBar.root, requireActivity().window)
        // 「看图自动横屏」每转一次都会重建页面；操作栏的显隐要跟过去，不能每转一次就重新弹出来。
        savedInstanceState?.let {
            chromeAutoHidePending = it.getBoolean(KEY_CHROME_AUTO_HIDE_PENDING, true)
            if (!it.getBoolean(KEY_CHROME_SHOWN, true)) chrome.setShown(false)
        }
        windowController = ComicWindowController(requireActivity(), binding.comicRoot, binding.comicWarmOverlay, savedInstanceState)
        windowController.apply()
        applyComicLoadingTint()
        chrome.applySystemBars()

        retryController = PageLoadRetryController(
            lifecycleOwner = viewLifecycleOwner,
            networkStateManager = requireNetworkStateManager(),
            totalPages = {
                (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: 0
            },
            onSummaryChanged = { loaded, total, failed ->
                renderImageLoadStatusBanner(
                    binding.comicTopBar.pageStatusRow,
                    binding.comicTopBar.pageStatusText,
                    loaded, total, failed,
                )
            },
            onRetryAt = { idx ->
                binding.comicPager.adapter?.notifyItemChanged(idx)
                binding.comicWebtoon.adapter?.notifyItemChanged(idx)
            },
        )

        wireSystemInsets()
        wireTopBar()
        wireBottomBar()
        wireBackPress()
        wireEventBus()
        wireViewModelEvents()

        val pagedAdapter = newAdapter()
        val webtoonAdapter = newAdapter()
        pagedViewport = PagedViewport(binding.comicPager, pagedAdapter, viewModel::onPageChanged)
        webtoonViewport = WebtoonViewport(binding.comicWebtoon, webtoonAdapter, viewModel::onPageChanged)
        pagedViewport.applyDirection()
        pagedViewport.applyTransformer()
        pagedViewport.applyOffscreenLimit()

        binding.comicBottomBar.comicSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) jumpToPage(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = cancelChromeAutoHide()
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            renderLoadState(state)
        }

        viewModel.currentPage.observe(viewLifecycleOwner) { idx ->
            updateProgressUi(idx)
            pagesProvider.currentIndex = idx
            updateImageOrientation()
        }

        ComicReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            // Settings 是 process-scoped 单例，可能在 Loaded 之前就发出 ChangeEvent（比如
            // 用户上次会话改过设置后立刻进入 reader），此时 [current] 还没初始化。
            // 所有依赖 current 的分支都需要 isInitialized 守卫。
            when (event) {
                ComicReaderSettings.ChangeEvent.Orientation -> updateImageOrientation()
                ComicReaderSettings.ChangeEvent.Layout -> {
                    pagedViewport.applyTransformer()
                    pagedViewport.applyDirection()
                    pagedViewport.applyOffscreenLimit()
                    val state = viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded ?: return@observe
                    val resume = if (::current.isInitialized) current.currentIndex()
                                 else viewModel.currentPage.value ?: 0
                    applyReadingMode(state.pages, resume)
                }
                ComicReaderSettings.ChangeEvent.Brightness,
                ComicReaderSettings.ChangeEvent.Theme,
                ComicReaderSettings.ChangeEvent.Interaction -> {
                    windowController.apply()
                    applyComicLoadingTint()
                    chrome.applySystemBars()
                }
                ComicReaderSettings.ChangeEvent.Image -> {
                    updateImageOrientation()
                    viewModel.onImageSettingsChanged()
                    if (::current.isInitialized) {
                        val idx = current.currentIndex()
                        binding.comicPager.adapter?.notifyItemChanged(idx)
                        binding.comicWebtoon.adapter?.notifyItemChanged(idx)
                    }
                }
            }
        }

        ObjectPool.getIllust(resolveIllustId()).observe(viewLifecycleOwner) { illust: Illust? ->
            illust?.title?.takeIf { it.isNotEmpty() }?.let { binding.comicTopBar.comicTitle.text = it }
        }

        viewModel.load()
    }

    // ---- Adapter factory ----------------------------------------------------

    private fun newAdapter(): ComicPagerAdapter = ComicPagerAdapter(
        lifecycleOwner = viewLifecycleOwner,
        urlResolver = { page -> viewModel.urlForPage(page) },
        pageSourceResolver = { page, url ->
            val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust
            PageImageSourceResolver.resolve(requireContext(), illust, page.index, url)
        },
        contentScaleProvider = {
            when (ComicReaderSettings.fitMode) {
                ComicReaderSettings.FitMode.FitWidth -> ContentScaleCompat.Companion.FillWidth
                ComicReaderSettings.FitMode.FitScreen -> ContentScaleCompat.Companion.Fit
                ComicReaderSettings.FitMode.FitOriginal -> ContentScaleCompat.Companion.Inside
            }
        },
        onSingleTap = ::handleSingleTap,
        onLongPressPage = ::showLongPressMenu,
        onPageStatusChanged = { idx, status -> retryController.reportStatus(idx, status) },
        indicatorColorProvider = ::comicIndicatorColor,
    )

    // 加载/进度环随黑白底着色:黑底用白环(与插画详情页一致),白底用深灰,避免白环不可见。
    private fun comicIndicatorColor(): Int =
        if (ComicReaderSettings.backgroundDark) Color.WHITE else 0xFF333333.toInt()

    private fun applyComicLoadingTint() {
        val c = comicIndicatorColor()
        binding.comicLoading.setIndicatorColor(c)
        binding.comicLoading.trackColor = ColorUtils.setAlphaComponent(c, 0x33)
    }

    // ---- Wiring -------------------------------------------------------------

    /** 操作栏上的按钮：用户碰过操作栏就不再自动收起它。 */
    private fun View.setOnChromeClickListener(action: () -> Unit) = setOnClickListener {
        cancelChromeAutoHide()
        action()
    }

    private fun wireTopBar() {
        binding.comicTopBar.comicBack.setOnChromeClickListener { activity?.finish() }
        binding.comicTopBar.comicShare.setOnChromeClickListener { shareCurrentIllust() }
        binding.comicTopBar.comicMore.setOnChromeClickListener { showOverflowMenu() }
        binding.comicTopBar.pageStatusRetry.setOnChromeClickListener { retryController.retryAllFailed() }
    }

    private fun wireBottomBar() {
        // 翻页方向不再放底栏(#1042 的误触源头),只在「阅读设置」面板里改；
        // 系列上一篇/下一篇挪进顶栏 ⋮ 菜单(showOverflowMenu)。
        binding.comicBottomBar.comicBtnPages.setOnChromeClickListener { showThumbsSheet() }
        binding.comicBottomBar.comicBtnSettings.setOnChromeClickListener {
            ComicReaderSettingsSheet().show(childFragmentManager, ComicReaderSettingsSheet.TAG)
        }
        binding.comicBottomBar.comicBtnTheme.setOnChromeClickListener {
            ComicReaderSettings.backgroundDark = !ComicReaderSettings.backgroundDark
        }
        binding.comicBottomBar.comicBtnSeriesList.setOnChromeClickListener { showSeriesListSheet() }
    }

    /**
     * 返回手势先收顶/底栏。callback 只在 chrome 显示时 enabled:常开会让系统放弃预测式返回动画,
     * chrome 收起后返回就是退出阅读器,这时必须把返回交还给系统才有跟手的退出预览。
     */
    private fun wireBackPress() {
        val cb = object : androidx.activity.OnBackPressedCallback(chrome.shown) {
            override fun handleOnBackPressed() {
                if (chrome.shown) { chrome.setShown(false); return }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        chrome.onShownChanged = { shown ->
            cancelChromeAutoHide()
            cb.isEnabled = shown
            refreshPageOverlay()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
    }

    private fun wireSystemInsets() {
        val overlayLp = binding.comicPageOverlay.layoutParams as ViewGroup.MarginLayoutParams
        val overlayTop = overlayLp.topMargin
        val overlayEnd = overlayLp.marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.comicRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.comicTopBar.root.updatePadding(top = bars.top)
            binding.comicBottomBar.root.updatePadding(bottom = bars.bottom)
            // 右上角页码要躲开状态栏、刘海和横屏时贴在侧边的导航栏。系统栏取「忽略可见性」的值:
            // 沉浸式下浮标露出的同时状态栏正在隐藏,按可见值算会先出现在状态栏下方、再往上跳一截。
            val overlayInsets = Insets.max(
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()),
                insets.getInsets(WindowInsetsCompat.Type.displayCutout()),
            )
            val endInset =
                if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) overlayInsets.left else overlayInsets.right
            binding.comicPageOverlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = overlayTop + overlayInsets.top
                marginEnd = overlayEnd + endInset
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.comicRoot)
    }

    private fun wireEventBus() {
        viewLifecycleOwner.lifecycleScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is ComicReaderEventBus.Event.JumpToPage -> jumpToPage(event.pageIndex)
                    is ComicReaderEventBus.Event.JumpToBookmark -> jumpToPage(event.entry.pageIndex)
                    ComicReaderEventBus.Event.AddBookmarkAtCurrent -> {
                        if (::current.isInitialized) viewModel.addBookmarkAt(current.currentIndex())
                    }
                }
            }
        }
    }

    private fun wireViewModelEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is ComicReaderV3ViewModel.UiEvent.Toast -> {
                        val msg = if (event.args.isEmpty()) getString(event.resId)
                        else getString(event.resId, *event.args.toTypedArray())
                        Toaster.showShort(msg)
                    }
                    is ComicReaderV3ViewModel.UiEvent.NavigateToReader -> {
                        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMIC_READER.key)
                            putExtra(Params.ILLUST_ID, event.illustId)
                        }
                        startActivity(intent)
                        activity?.finish()
                    }
                    ComicReaderV3ViewModel.UiEvent.DismissAndFinish -> activity?.finish()
                }
            }
        }
    }

    // ---- Render -------------------------------------------------------------

    private fun renderLoadState(state: ComicReaderV3ViewModel.LoadState) {
        binding.comicLoading.visibility =
            if (state is ComicReaderV3ViewModel.LoadState.Loading) View.VISIBLE else View.GONE
        binding.comicError.visibility =
            if (state is ComicReaderV3ViewModel.LoadState.Error) View.VISIBLE else View.GONE
        if (state is ComicReaderV3ViewModel.LoadState.Error) {
            binding.comicError.text = getString(R.string.comic_reader_load_failed, state.message)
        }
        if (state is ComicReaderV3ViewModel.LoadState.Loaded) {
            binding.comicTopBar.comicTitle.text = state.illust.title.orEmpty()
            binding.comicBottomBar.comicSeekbar.max = (state.pages.size - 1).coerceAtLeast(0)
            binding.comicBottomBar.comicTotalLabel.text = state.pages.size.toString()
            pagesProvider.pages = state.pages
            pagesProvider.currentIndex = viewModel.currentPage.value ?: 0
            pagesProvider.title = state.illust.title.orEmpty()
            applyReadingMode(state.pages, viewModel.currentPage.value ?: 0)
            retryController.refresh()
            updateImageOrientation()
            scheduleChromeAutoHide()
        }
    }

    /** 首次打开时操作栏无操作 [CHROME_AUTO_HIDE_MS] 后自动收起(#1172)，从内容加载好开始计时。 */
    private fun scheduleChromeAutoHide() {
        if (!chromeAutoHidePending || !chrome.shown) return
        chromeAutoHideJob?.cancel()
        chromeAutoHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CHROME_AUTO_HIDE_MS)
            chrome.setShown(false)
        }
    }

    private fun cancelChromeAutoHide() {
        chromeAutoHidePending = false
        chromeAutoHideJob?.cancel()
        chromeAutoHideJob = null
    }

    private fun applyReadingMode(pages: List<ComicReaderV3ViewModel.ComicPage>, resumeIndex: Int) {
        when (ComicReaderSettings.readingMode) {
            ComicReaderSettings.ReadingMode.Paged -> {
                webtoonViewport.deactivate()
                pagedViewport.activate(pages, resumeIndex)
                current = pagedViewport
            }
            ComicReaderSettings.ReadingMode.Webtoon -> {
                pagedViewport.deactivate()
                webtoonViewport.activate(pages, resumeIndex)
                current = webtoonViewport
            }
        }
    }

    private fun jumpToPage(index: Int) {
        if (::current.isInitialized) current.jumpTo(index)
    }

    private fun updateProgressUi(index: Int) {
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: 0
        if (total <= 0) return
        binding.comicBottomBar.comicProgressLabel.text = (index + 1).toString()
        binding.comicBottomBar.comicSeekbar.max = (total - 1).coerceAtLeast(0)
        binding.comicBottomBar.comicSeekbar.progress = index.coerceIn(0, binding.comicBottomBar.comicSeekbar.max)
        binding.comicPageOverlay.text = getString(R.string.comic_reader_page_indicator, index + 1, total)
        refreshPageOverlay()
    }

    /**
     * 页码浮标只在 chrome 收起时露出(#1058)。展开时它会被半透明的顶栏盖住、数字透出来糊成一团;
     * 而且底栏左右两端本来就是「当前页 / 总页」,再叠一层纯属重复。与小说阅读器的常驻进度(#994)
     * 同一条规则。放在右上角而不是贴底(#1222):画面中下部常是对白框和人物,右上角通常是空的。
     */
    private fun refreshPageOverlay() {
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: 0
        binding.comicPageOverlay.visibility =
            if (ComicReaderSettings.showPageNumber && total > 1 && !chrome.shown) View.VISIBLE else View.GONE
    }

    // ---- Tap zone -----------------------------------------------------------

    private fun handleSingleTap(zone: ComicPagerAdapter.TapZone) {
        if (ComicReaderSettings.readingMode == ComicReaderSettings.ReadingMode.Webtoon) {
            chrome.toggle(); return
        }
        // 操作栏展开时点哪儿都只收起它，不翻页(#1172 误触)；放大中的页已由 adapter 报成 Center。
        if (chrome.shown) {
            chrome.setShown(false); return
        }
        // 横屏握持时拇指自然落在屏幕偏侧,想点中间呼出操作栏常被判成翻页(#1222),可在设置里关掉。
        if (!ComicReaderSettings.landscapeTapFlip &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            chrome.toggle(); return
        }
        val left = if (ComicReaderSettings.tapZoneReversed) ComicPagerAdapter.TapZone.Right else ComicPagerAdapter.TapZone.Left
        val right = if (ComicReaderSettings.tapZoneReversed) ComicPagerAdapter.TapZone.Left else ComicPagerAdapter.TapZone.Right
        when (zone) {
            ComicPagerAdapter.TapZone.Center -> chrome.toggle()
            left -> stepAndApply(forward = false)
            right -> stepAndApply(forward = true)
            else -> chrome.toggle()
        }
    }

    private fun stepAndApply(forward: Boolean) {
        if (!::current.isInitialized) return
        viewModel.stepTarget(forward)?.let { current.jumpTo(it, animated = true) }
    }

    // ---- Menus / Sheets -----------------------------------------------------

    private fun shareCurrentIllust() {
        val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust ?: return
        object : ShareIllust(requireContext(), illust) {
            override fun onPrepare() {}
        }.execute()
    }

    /** 顶栏 ⋮：书签 / 评论，有系列时再挂 系列上一篇/下一篇。分享不重复挂——顶栏已有常驻分享按钮。 */
    private fun showOverflowMenu() {
        val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust
        val hasSeries = illust?.series?.let { it.id != 0L } == true
        showV3Menu {
            item(getString(R.string.comic_reader_bookmarks_button), R.drawable.ic_baseline_bookmark_24) { showBookmarksSheet() }
            if (hasSeries) {
                item(getString(R.string.comic_reader_prev_series), R.drawable.ic_baseline_arrow_back_ios_24) {
                    viewModel.jumpSeriesNeighbor(forward = false)
                }
                item(getString(R.string.comic_reader_next_series), R.drawable.ic_chevron_right_black_24dp) {
                    viewModel.jumpSeriesNeighbor(forward = true)
                }
            }
            item(getString(R.string.view_comments), R.drawable.ic_baseline_comment_24) {
                val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
                    putExtra(Params.ILLUST_ID, resolveIllustId().toInt())
                }
                startActivity(intent)
            }
        }
    }

    private fun showBookmarksSheet() {
        ComicBookmarksSheet.newInstance(resolveIllustId())
            .show(childFragmentManager, ComicBookmarksSheet.TAG)
    }

    private fun showSeriesListSheet() {
        val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust
        val series = illust?.series
        if (series == null || series.id == 0L) {
            Toaster.showShort(R.string.comic_reader_no_series)
            return
        }
        ComicSeriesListSheet.newInstance(
            seriesId = series.id,
            currentIllustId = resolveIllustId(),
            seriesTitle = series.title,
        ).show(childFragmentManager, ComicSeriesListSheet.TAG)
    }

    private fun showThumbsSheet() {
        val pages = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages
        if (pages.isNullOrEmpty()) {
            Toaster.showShort(R.string.comic_reader_no_pages); return
        }
        ComicThumbsSheet().show(childFragmentManager, ComicThumbsSheet.TAG)
    }

    private fun showLongPressMenu(pageIndex: Int) {
        val state = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded) ?: return
        val illust = state.illust
        val activity = (activity as? BaseActivity<*>) ?: return
        showV3Menu {
            item(getString(R.string.comic_reader_long_press_save), R.drawable.ic_baseline_get_app_24) {
                IllustDownload.downloadIllustCertainPage(illust, pageIndex, activity)
                if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isBookmarked) {
                    PixivOperate.postLikeDefaultStarType(illust)
                }
            }
            item(getString(R.string.comic_reader_long_press_share), R.drawable.ic_share_black_24dp) {
                shareCurrentIllust()
            }
            item(getString(R.string.comic_reader_long_press_bookmark), R.drawable.ic_baseline_bookmark_24) {
                viewModel.addBookmarkAt(pageIndex)
            }
            item(getString(R.string.comic_reader_long_press_open_advanced), R.drawable.ic_baseline_settings_24) {
                val intent = Intent(requireContext(), ceui.lisa.activities.ImageDetailActivity::class.java).apply {
                    putExtra("illust", illust)
                    putExtra("dataType", "二级详情")
                    putExtra("index", pageIndex)
                }
                startActivity(intent)
            }
        }
    }

    // ---- Lifecycle / volume keys -------------------------------------------

    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!ComicReaderSettings.volumeKeyFlip) return false
        if (!::current.isInitialized) return false
        stepAndApply(forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        return true
    }

    override fun onResume() {
        super.onResume()
        viewModel.onSessionStart()
        updateImageOrientation()
    }

    override fun onPause() {
        orientationJob?.cancel()
        super.onPause()
        viewModel.onSessionFlush()
    }

    private fun updateImageOrientation() {
        orientationJob?.cancel()
        if (!isResumed) return
        if (!ComicReaderSettings.autoRotateImage) {
            windowController.restoreOrientation()
            return
        }
        val state = viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded ?: return
        val index = viewModel.currentPage.value ?: 0
        val page = state.pages.getOrNull(index) ?: return
        orientationJob = viewLifecycleOwner.lifecycleScope.launch {
            // 快速翻页时只处理停留的这一页；走统一的来源判定：本地已下载 / 已缓存就直接读，
            // 不额外下载（需要网络时才等共享任务下完）。
            delay(400)
            val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust
                ?: return@launch
            val bounds = quickPageBounds(illust, page) ?: awaitPageBounds(illust, page) ?: return@launch
            if (isResumed && viewModel.currentPage.value == index && ComicReaderSettings.autoRotateImage) {
                windowController.applyImageOrientation(bounds.first, bounds.second)
            }
        }
    }

    /**
     * 不等这一页下完就能知道的尺寸(#1172)：首页用作品元数据里的宽高；其余页看预览图是否已在本地
     * （详情页多半看过）。都没有返回 null，由 [awaitPageBounds] 兜底。
     */
    private suspend fun quickPageBounds(illust: Illust, page: ComicReaderV3ViewModel.ComicPage): Pair<Int, Int>? {
        if (page.index == 0 && illust.width > 0 && illust.height > 0) return illust.width to illust.height
        val preview = PageImageSourceResolver.resolveCheap(page.previewUrl)?.file ?: return null
        return decodeBounds(preview)
    }

    private suspend fun awaitPageBounds(illust: Illust, page: ComicReaderV3ViewModel.ComicPage): Pair<Int, Int>? {
        val file =
            try {
                PageImageSourceResolver.resolve(
                    requireContext(), illust, page.index, viewModel.urlForPage(page),
                ).awaitFile(requireContext())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("ComicReaderV3").w(error, "orientation probe failed page=%d", page.index)
                null
            } ?: return null
        return decodeBounds(file)
    }

    /** 只读文件头拿宽高；解不出来(坏文件)返回 null。 */
    private suspend fun decodeBounds(file: File): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }
        (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::windowController.isInitialized) windowController.saveState(outState)
        if (::chrome.isInitialized) outState.putBoolean(KEY_CHROME_SHOWN, chrome.shown)
        outState.putBoolean(KEY_CHROME_AUTO_HIDE_PENDING, chromeAutoHidePending)
    }

    override fun onDestroyView() {
        orientationJob?.cancel()
        // Activity finish 自然恢复下面页面的方向；配置重建期间不能还原，否则会来回旋转。
        if (!requireActivity().isChangingConfigurations && !requireActivity().isFinishing) {
            windowController.restoreOrientation()
        }
        super.onDestroyView()
    }

    private fun resolveIllustId(): Long = arguments?.getLong(ARG_ILLUST_ID, 0L) ?: 0L

    companion object {
        private const val ARG_ILLUST_ID = "illust_id"
        private const val KEY_CHROME_SHOWN = "comic_chrome_shown"
        private const val KEY_CHROME_AUTO_HIDE_PENDING = "comic_chrome_auto_hide_pending"
        private const val CHROME_AUTO_HIDE_MS = 3_000L

        @JvmStatic
        fun newInstance(illustId: Long): ComicReaderV3Fragment = ComicReaderV3Fragment().apply {
            arguments = Bundle().apply { putLong(ARG_ILLUST_ID, illustId) }
        }
    }
}
