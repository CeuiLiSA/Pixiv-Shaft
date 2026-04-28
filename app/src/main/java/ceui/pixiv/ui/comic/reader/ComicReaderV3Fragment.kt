package ceui.pixiv.ui.comic.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
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
import ceui.lisa.models.IllustsBean
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chrome = ComicChrome(binding.comicTopBar.root, binding.comicBottomBar.root, requireActivity().window)
        windowController = ComicWindowController(requireActivity().window, binding.comicRoot, binding.comicWarmOverlay)
        windowController.apply()
        chrome.applySystemBars()

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
            when (event) {
                ComicReaderSettings.ChangeEvent.Layout -> {
                    pagedViewport.applyTransformer()
                    pagedViewport.applyDirection()
                    pagedViewport.applyOffscreenLimit()
                    val state = viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded ?: return@observe
                    applyReadingMode(state.pages, current.currentIndex())
                }
                ComicReaderSettings.ChangeEvent.Brightness,
                ComicReaderSettings.ChangeEvent.Theme,
                ComicReaderSettings.ChangeEvent.Interaction -> {
                    windowController.apply()
                    chrome.applySystemBars()
                }
                ComicReaderSettings.ChangeEvent.Image -> {
                    viewModel.onImageSettingsChanged()
                    binding.comicPager.adapter?.notifyItemChanged(current.currentIndex())
                    binding.comicWebtoon.adapter?.notifyItemChanged(current.currentIndex())
                }
            }
        }

        ObjectPool.getIllust(resolveIllustId()).observe(viewLifecycleOwner) { illust: IllustsBean? ->
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
    )

    // ---- Wiring -------------------------------------------------------------

    private fun wireTopBar() {
        binding.comicTopBar.comicBack.setOnClickListener { activity?.finish() }
        binding.comicTopBar.comicShare.setOnClickListener { shareCurrentIllust() }
        binding.comicTopBar.comicMore.setOnClickListener { showOverflowMenu() }
    }

    private fun wireBottomBar() {
        binding.comicBottomBar.comicBtnPages.setOnClickListener { showThumbsSheet() }
        binding.comicBottomBar.comicBtnDirection.setOnClickListener {
            ComicReaderSettings.toggleDirection()
            pagedViewport.applyDirection()
        }
        binding.comicBottomBar.comicBtnSettings.setOnClickListener {
            ComicReaderSettingsSheet().show(childFragmentManager, ComicReaderSettingsSheet.TAG)
        }
        binding.comicBottomBar.comicBtnTheme.setOnClickListener {
            ComicReaderSettings.backgroundDark = !ComicReaderSettings.backgroundDark
        }
        binding.comicBottomBar.comicBtnPrevSeries.setOnClickListener {
            viewModel.jumpSeriesNeighbor(forward = false)
        }
        binding.comicBottomBar.comicBtnNextSeries.setOnClickListener {
            viewModel.jumpSeriesNeighbor(forward = true)
        }
    }

    private fun wireBackPress() {
        val cb = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chrome.shown) { chrome.setShown(false); return }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
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
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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
            applyReadingMode(state.pages, viewModel.currentPage.value ?: 0)
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
        binding.comicPageOverlay.visibility =
            if (ComicReaderSettings.showPageNumber && total > 1) View.VISIBLE else View.GONE
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

    private fun showThumbsSheet() {
        val pages = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages
        if (pages.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.comic_reader_no_pages, Toast.LENGTH_SHORT).show(); return
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
                if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
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
