package ceui.pixiv.ui.comic.reader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import ceui.loxia.ObjectPool
import ceui.loxia.requireNetworkStateManager
import ceui.loxia.Illust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import com.hjq.toast.Toaster
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chrome = ComicChrome(binding.comicTopBar.root, binding.comicBottomBar.root, requireActivity().window)
        windowController = ComicWindowController(requireActivity().window, binding.comicRoot, binding.comicWarmOverlay)
        windowController.apply()
        applyComicLoadingTint()
        chrome.applySystemBars()

        retryController = PageLoadRetryController(
            lifecycleOwner = viewLifecycleOwner,
            networkStateManager = requireNetworkStateManager(),
            urlAtIndex = { idx ->
                (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)
                    ?.pages?.getOrNull(idx)?.let { viewModel.urlForPage(it) }
            },
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
        applyDirectionIcon()

        binding.comicBottomBar.comicSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) jumpToPage(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            renderLoadState(state)
        }

        viewModel.currentPage.observe(viewLifecycleOwner) { idx ->
            updateProgressUi(idx)
            pagesProvider.currentIndex = idx
        }

        ComicReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            // Settings 是 process-scoped 单例，可能在 Loaded 之前就发出 ChangeEvent（比如
            // 用户上次会话改过设置后立刻进入 reader），此时 [current] 还没初始化。
            // 所有依赖 current 的分支都需要 isInitialized 守卫。
            when (event) {
                ComicReaderSettings.ChangeEvent.Layout -> {
                    pagedViewport.applyTransformer()
                    pagedViewport.applyDirection()
                    pagedViewport.applyOffscreenLimit()
                    applyDirectionIcon()
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

    private fun isRtl(): Boolean =
        ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.RTL

    private fun currentDirectionLabelRes(): Int =
        if (isRtl()) R.string.comic_reader_dir_rtl else R.string.comic_reader_dir_ltr

    /** 底栏翻页方向按钮的图标跟随当前方向(LTR → 向右箭头,RTL → 向左箭头),让状态一眼可见。 */
    private fun applyDirectionIcon() {
        binding.comicBottomBar.comicBtnDirection.setImageResource(
            if (isRtl()) R.drawable.ic_reader_dir_rtl else R.drawable.ic_reader_dir_ltr
        )
    }

    // 加载/进度环随黑白底着色:黑底用白环(与插画详情页一致),白底用深灰,避免白环不可见。
    private fun comicIndicatorColor(): Int =
        if (ComicReaderSettings.backgroundDark) Color.WHITE else 0xFF333333.toInt()

    private fun applyComicLoadingTint() {
        val c = comicIndicatorColor()
        binding.comicLoading.setIndicatorColor(c)
        binding.comicLoading.trackColor = ColorUtils.setAlphaComponent(c, 0x33)
    }

    // ---- Wiring -------------------------------------------------------------

    private fun wireTopBar() {
        binding.comicTopBar.comicBack.setOnClickListener { activity?.finish() }
        binding.comicTopBar.comicShare.setOnClickListener { shareCurrentIllust() }
        binding.comicTopBar.comicMore.setOnClickListener { showOverflowMenu() }
        binding.comicTopBar.pageStatusRetry.setOnClickListener { retryController.retryAllFailed() }
    }

    private fun wireBottomBar() {
        binding.comicBottomBar.comicBtnPages.setOnClickListener { showThumbsSheet() }
        binding.comicBottomBar.comicBtnDirection.setOnClickListener {
            ComicReaderSettings.toggleDirection()
            pagedViewport.applyDirection()
            applyDirectionIcon()
            // issue #1042:这颗按钮原先和「系列」同图标、按下无任何反馈,误触一下方向就静默翻了,
            // 用户体感是「设置不记忆、重开又变回去」。切换时明确告知当前方向。
            Toaster.showShort(
                getString(R.string.comic_reader_direction) + ": " + getString(currentDirectionLabelRes())
            )
        }
        binding.comicBottomBar.comicBtnSettings.setOnClickListener {
            ComicReaderSettingsSheet().show(childFragmentManager, ComicReaderSettingsSheet.TAG)
        }
        binding.comicBottomBar.comicBtnTheme.setOnClickListener {
            ComicReaderSettings.backgroundDark = !ComicReaderSettings.backgroundDark
        }
        binding.comicBottomBar.comicBtnSeriesList.setOnClickListener { showSeriesListSheet() }
        binding.comicBottomBar.comicBtnPrevSeries.setOnClickListener {
            viewModel.jumpSeriesNeighbor(forward = false)
        }
        binding.comicBottomBar.comicBtnNextSeries.setOnClickListener {
            viewModel.jumpSeriesNeighbor(forward = true)
        }
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
            cb.isEnabled = shown
            refreshPageOverlay()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
    }

    private fun wireSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.comicRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.comicTopBar.root.updatePadding(top = bars.top)
            binding.comicBottomBar.root.updatePadding(bottom = bars.bottom)
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
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
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
        }
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
     * 贴底页码浮标只在 chrome 收起时露出(#1058)。底栏的背景是半透明的 #CC000000,展开时正好
     * 盖在这个距底 14dp 的浮标上,数字透出来糊成一团灰字、和「翻页方向」图标叠在一起;而且底栏
     * 左右两端本来就是「当前页 / 总页」,再叠一层纯属重复。与小说阅读器的常驻进度(#994)同一条规则。
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
        if (viewModel.stepPage(forward)) {
            current.jumpTo(viewModel.currentPage.value ?: 0)
        }
    }

    // ---- Menus / Sheets -----------------------------------------------------

    private fun shareCurrentIllust() {
        val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust ?: return
        object : ShareIllust(requireContext(), illust) {
            override fun onPrepare() {}
        }.execute()
    }

    private fun showOverflowMenu() {
        showV3Menu {
            item(getString(R.string.comic_reader_bookmarks_button), R.drawable.ic_baseline_bookmark_24) { showBookmarksSheet() }
            item(getString(R.string.string_110), R.drawable.ic_share_black_24dp) { shareCurrentIllust() }
            item(getString(R.string.view_comments), R.drawable.ic_baseline_comment_24) {
                val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
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
        val forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        return if (viewModel.stepPage(forward)) {
            current.jumpTo(viewModel.currentPage.value ?: 0); true
        } else true
    }

    override fun onResume() {
        super.onResume()
        viewModel.onSessionStart()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onSessionFlush()
    }

    private fun resolveIllustId(): Long = arguments?.getLong(ARG_ILLUST_ID, 0L) ?: 0L

    companion object {
        private const val ARG_ILLUST_ID = "illust_id"

        @JvmStatic
        fun newInstance(illustId: Long): ComicReaderV3Fragment = ComicReaderV3Fragment().apply {
            arguments = Bundle().apply { putLong(ARG_ILLUST_ID, illustId) }
        }
    }
}
