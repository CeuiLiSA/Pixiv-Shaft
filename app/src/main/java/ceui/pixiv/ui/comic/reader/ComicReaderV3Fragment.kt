package ceui.pixiv.ui.comic.reader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.ComicBookmarkEntity
import ceui.lisa.database.ComicReadingStatsEntity
import ceui.lisa.databinding.FragmentComicReaderV3Binding
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.loxia.ObjectPool
import ceui.lisa.models.IllustsBean
import ceui.lisa.activities.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ComicReaderV3Fragment : Fragment(R.layout.fragment_comic_reader_v3),
    ComicBookmarkCallback, ComicThumbsCallback {

    private val binding by viewBinding(FragmentComicReaderV3Binding::bind)
    private val viewModel: ComicReaderV3ViewModel by viewModels {
        ComicReaderV3ViewModel.factory(resolveIllustId())
    }

    private lateinit var pagerAdapter: ComicPagerAdapter
    private lateinit var webtoonAdapter: ComicPagerAdapter

    private var chromeShown = true
    private var pendingResumeIndex: Int? = null

    private var sessionStartMs: Long = 0L
    private var sessionFlips: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyImmersiveAndBrightness()
        wireSystemInsets()
        wireTopBar()
        wireBottomBar()
        wireBackPress()

        pagerAdapter = ComicPagerAdapter(::handleSingleTap, ::showLongPressMenu).also { it.fillHeight = true }
        webtoonAdapter = ComicPagerAdapter(::handleSingleTap, ::showLongPressMenu).also { it.fillHeight = false }

        binding.comicPager.adapter = pagerAdapter
        binding.comicPager.offscreenPageLimit = ComicReaderSettings.preloadAhead.coerceAtLeast(1)
        binding.comicPager.layoutDirection = if (ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.RTL)
            View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        applyPageTransformer()
        binding.comicPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.onPageChanged(position)
                updateProgressUi(position)
            }
        })

        binding.comicWebtoon.layoutManager = LinearLayoutManager(requireContext())
        binding.comicWebtoon.adapter = webtoonAdapter
        binding.comicWebtoon.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val pos = lm.findFirstVisibleItemPosition()
                if (pos >= 0) {
                    viewModel.onPageChanged(pos)
                    updateProgressUi(pos)
                }
            }
        })

        binding.comicBottomBar.comicSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                jumpToPage(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })

        applyReadingMode()

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            binding.comicLoading.isVisible = state is ComicReaderV3ViewModel.LoadState.Loading
            binding.comicError.isVisible = state is ComicReaderV3ViewModel.LoadState.Error
            if (state is ComicReaderV3ViewModel.LoadState.Error) {
                binding.comicError.text = getString(R.string.comic_reader_load_failed, state.message)
            }
            if (state is ComicReaderV3ViewModel.LoadState.Loaded) {
                binding.comicTopBar.comicTitle.text = state.illust.title.orEmpty()
                pagerAdapter.submitList(state.pages)
                webtoonAdapter.submitList(state.pages)
                binding.comicBottomBar.comicSeekbar.max = (state.pages.size - 1).coerceAtLeast(0)
                binding.comicBottomBar.comicTotalLabel.text = state.pages.size.toString()
                pendingResumeIndex = viewModel.currentPage.value ?: 0
                applyReadingMode()
            }
        }

        var lastObservedPage = -1
        viewModel.currentPage.observe(viewLifecycleOwner) { idx ->
            if (lastObservedPage >= 0 && idx != lastObservedPage) sessionFlips++
            lastObservedPage = idx
            updateProgressUi(idx)
        }

        ComicReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            when (event) {
                ComicReaderSettings.ChangeEvent.Layout -> {
                    applyReadingMode()
                    applyPageTransformer()
                }
                ComicReaderSettings.ChangeEvent.Brightness, ComicReaderSettings.ChangeEvent.Theme,
                ComicReaderSettings.ChangeEvent.Interaction -> applyImmersiveAndBrightness()
                ComicReaderSettings.ChangeEvent.Image -> {
                    pagerAdapter.notifyDataSetChanged()
                    webtoonAdapter.notifyDataSetChanged()
                }
            }
        }

        // 应用初始进度（若 ObjectPool 已有 IllustsBean，title 立即可用）
        ObjectPool.getIllust(resolveIllustId()).observe(viewLifecycleOwner) { illust: IllustsBean? ->
            illust?.title?.takeIf { it.isNotEmpty() }?.let { binding.comicTopBar.comicTitle.text = it }
        }

        viewModel.load()
    }

    // ---- Wiring -------------------------------------------------------------

    private fun wireTopBar() {
        binding.comicTopBar.comicBack.setOnClickListener { activity?.finish() }
        binding.comicTopBar.comicShare.setOnClickListener {
            shareCurrentIllust()
        }
        binding.comicTopBar.comicMore.setOnClickListener { showOverflowMenu() }
    }

    private fun wireBottomBar() {
        binding.comicBottomBar.comicBtnPages.setOnClickListener { showThumbsSheet() }
        binding.comicBottomBar.comicBtnPrevSeries.setOnClickListener { jumpSeriesNeighbor(forward = false) }
        binding.comicBottomBar.comicBtnNextSeries.setOnClickListener { jumpSeriesNeighbor(forward = true) }
        binding.comicBottomBar.comicBtnDirection.setOnClickListener {
            ComicReaderSettings.toggleDirection()
            binding.comicPager.layoutDirection =
                if (ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.RTL)
                    View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        binding.comicBottomBar.comicBtnSettings.setOnClickListener {
            ComicReaderSettingsSheet().show(childFragmentManager, ComicReaderSettingsSheet.TAG)
        }
        binding.comicBottomBar.comicBtnTheme.setOnClickListener {
            ComicReaderSettings.backgroundDark = !ComicReaderSettings.backgroundDark
            binding.comicRoot.setBackgroundColor(if (ComicReaderSettings.backgroundDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun shareCurrentIllust() {
        val illust = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.illust ?: return
        object : ShareIllust(requireContext(), illust) {
            override fun onPrepare() {}
        }.execute()
    }

    private fun wireBackPress() {
        val cb = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chromeShown && !ComicReaderSettings.immersive.not()) {
                    // 沉浸式开启时优先收起 chrome
                    if (chromeShown) { setChromeShown(false); return }
                }
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

    // ---- Mode / progress ----------------------------------------------------

    private fun applyReadingMode() {
        when (ComicReaderSettings.readingMode) {
            ComicReaderSettings.ReadingMode.Paged -> {
                binding.comicPager.isVisible = true
                binding.comicWebtoon.isVisible = false
                pendingResumeIndex?.let { idx ->
                    binding.comicPager.post { binding.comicPager.setCurrentItem(idx, false) }
                    pendingResumeIndex = null
                }
            }
            ComicReaderSettings.ReadingMode.Webtoon -> {
                binding.comicPager.isVisible = false
                binding.comicWebtoon.isVisible = true
                pendingResumeIndex?.let { idx ->
                    (binding.comicWebtoon.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, 0)
                    pendingResumeIndex = null
                }
            }
        }
    }

    private fun jumpToPage(page: Int) {
        when (ComicReaderSettings.readingMode) {
            ComicReaderSettings.ReadingMode.Paged ->
                binding.comicPager.setCurrentItem(page, false)
            ComicReaderSettings.ReadingMode.Webtoon ->
                (binding.comicWebtoon.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(page, 0)
        }
    }

    private fun updateProgressUi(index: Int) {
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: 0
        if (total <= 0) return
        binding.comicBottomBar.comicProgressLabel.text = (index + 1).toString()
        binding.comicBottomBar.comicSeekbar.max = (total - 1).coerceAtLeast(0)
        binding.comicBottomBar.comicSeekbar.progress = index.coerceIn(0, binding.comicBottomBar.comicSeekbar.max)
        binding.comicPageOverlay.text = getString(R.string.comic_reader_page_indicator, index + 1, total)
        binding.comicPageOverlay.isVisible = ComicReaderSettings.showPageNumber && total > 1
    }

    // ---- Chrome -------------------------------------------------------------

    /**
     * 单击调度：左 1/3 → 上一页，中 1/3 → 切 chrome，右 1/3 → 下一页。
     * tapZoneReversed 时左右互换。Webtoon 模式下没有「上下页」概念，直接 toggle chrome。
     */
    private fun handleSingleTap(zone: ComicPagerAdapter.TapZone) {
        if (ComicReaderSettings.readingMode == ComicReaderSettings.ReadingMode.Webtoon) {
            toggleChrome(); return
        }
        val left = if (ComicReaderSettings.tapZoneReversed) ComicPagerAdapter.TapZone.Right else ComicPagerAdapter.TapZone.Left
        val right = if (ComicReaderSettings.tapZoneReversed) ComicPagerAdapter.TapZone.Left else ComicPagerAdapter.TapZone.Right
        when (zone) {
            ComicPagerAdapter.TapZone.Center -> toggleChrome()
            left -> stepPage(forward = false)
            right -> stepPage(forward = true)
            else -> toggleChrome()
        }
    }

    private fun stepPage(forward: Boolean) {
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: return
        val current = viewModel.currentPage.value ?: 0
        val target = if (forward) current + 1 else current - 1
        if (target in 0 until total) {
            jumpToPage(target)
            sessionFlips++
        }
    }

    private fun toggleChrome() = setChromeShown(!chromeShown)

    private fun setChromeShown(shown: Boolean) {
        chromeShown = shown
        binding.comicTopBar.root.animate().alpha(if (shown) 1f else 0f).setDuration(150).withStartAction {
            if (shown) binding.comicTopBar.root.isVisible = true
        }.withEndAction {
            if (!shown) binding.comicTopBar.root.isVisible = false
        }.start()
        binding.comicBottomBar.root.animate().alpha(if (shown) 1f else 0f).setDuration(150).withStartAction {
            if (shown) binding.comicBottomBar.root.isVisible = true
        }.withEndAction {
            if (!shown) binding.comicBottomBar.root.isVisible = false
        }.start()
        applyImmersiveAndBrightness()
    }

    // ---- Brightness / immersive --------------------------------------------

    private fun applyImmersiveAndBrightness() {
        val window = activity?.window ?: return
        binding.comicRoot.keepScreenOn = ComicReaderSettings.keepScreenOn

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (ComicReaderSettings.immersive && !chromeShown) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        val lp = window.attributes
        lp.screenBrightness = if (ComicReaderSettings.useSystemBrightness) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else ComicReaderSettings.customBrightness.coerceIn(0.01f, 1f)
        window.attributes = lp

        binding.comicRoot.setBackgroundColor(if (ComicReaderSettings.backgroundDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        binding.comicWarmOverlay.alpha = ComicReaderSettings.warmFilterStrength.coerceIn(0f, 0.6f)
    }

    // ---- Menus --------------------------------------------------------------

    private fun showOverflowMenu() {
        showV3Menu {
            item(getString(R.string.comic_reader_bookmarks_button), R.drawable.ic_baseline_bookmark_24) {
                showBookmarksSheet()
            }
            item(getString(R.string.string_110), R.drawable.ic_share_black_24dp) {
                shareCurrentIllust()
            }
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
        val urls = pages.map { it.previewUrl }
        val current = viewModel.currentPage.value ?: 0
        ComicThumbsSheet.newInstance(urls, current).show(childFragmentManager, ComicThumbsSheet.TAG)
    }

    override fun onPagePicked(index: Int) { jumpToPage(index) }

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
                addBookmarkAt(pageIndex)
            }
            item(getString(R.string.comic_reader_long_press_open_advanced), R.drawable.ic_baseline_settings_24) {
                // AI 抠图 / 漫画翻译 / 画质增强等强依赖于 ImageDetailActivity 的 overlay
                // 与生命周期。直接跳到二级详情页对应 page，复用既有的 AI 入口。
                val intent = Intent(requireContext(), ceui.lisa.activities.ImageDetailActivity::class.java).apply {
                    putExtra("illust", illust)
                    putExtra("dataType", "二级详情")
                    putExtra("index", pageIndex)
                }
                startActivity(intent)
            }
        }
    }

    private fun jumpSeriesNeighbor(forward: Boolean) {
        val state = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded) ?: return
        val series = state.illust.series
        val seriesId = series?.id?.toLong() ?: 0L
        if (seriesId == 0L) {
            Toast.makeText(requireContext(), R.string.comic_reader_no_series, Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(requireContext(), R.string.comic_reader_series_loading, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val neighborId = withContext(Dispatchers.IO) {
                ComicSeriesNeighborFinder.findNeighbor(seriesId, resolveIllustId(), forward)
            }
            if (neighborId == null || neighborId == 0L) {
                Toast.makeText(
                    requireContext(),
                    if (forward) R.string.comic_reader_series_last else R.string.comic_reader_series_first,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
                putExtra(Params.ILLUST_ID, neighborId)
            }
            startActivity(intent)
            activity?.finish()
        }
    }

    override fun onJumpToBookmark(entry: ComicBookmarkEntity) { jumpToPage(entry.pageIndex) }

    override fun onAddBookmarkAtCurrentPage() {
        val current = viewModel.currentPage.value ?: 0
        addBookmarkAt(current)
    }

    override fun onDeleteBookmark(entry: ComicBookmarkEntity) {
        // sheet 已自行删除；这里无需额外处理。
    }

    private fun addBookmarkAt(pageIndex: Int) {
        val state = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded) ?: return
        val page = state.pages.getOrNull(pageIndex) ?: return
        val illustId = resolveIllustId()
        val total = state.pages.size
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).comicBookmarkDao().insert(
                    ComicBookmarkEntity(
                        illustId, pageIndex, total,
                        page.previewUrl, "",
                        System.currentTimeMillis(),
                    )
                )
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.comic_reader_bookmarks_added, pageIndex + 1),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun applyPageTransformer() {
        val transformer = when (ComicReaderSettings.flipAnim) {
            ComicReaderSettings.FlipAnim.Slide -> ComicPageTransformers.Slide
            ComicReaderSettings.FlipAnim.Cover -> ComicPageTransformers.Cover
            ComicReaderSettings.FlipAnim.Depth -> ComicPageTransformers.Depth
            ComicReaderSettings.FlipAnim.FlipBook -> ComicPageTransformers.FlipBook
        }
        binding.comicPager.setPageTransformer(transformer)
    }


    // ---- Volume keys --------------------------------------------------------

    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!ComicReaderSettings.volumeKeyFlip) return false
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: return false
        val current = viewModel.currentPage.value ?: 0
        val next = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) current + 1 else current - 1
        if (next < 0 || next >= total) return true
        jumpToPage(next)
        sessionFlips++
        return true
    }

    override fun onResume() {
        super.onResume()
        sessionStartMs = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        flushSessionStats()
    }

    /** 把本次会话的阅读时长 / 翻页数累加到 illust 的统计行里，写完归零。 */
    private fun flushSessionStats() {
        val now = System.currentTimeMillis()
        val durationMs = (now - sessionStartMs).coerceAtLeast(0L)
        if (durationMs <= 0L && sessionFlips <= 0) return
        val flips = sessionFlips
        val illustId = resolveIllustId()
        val state = viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded
        val total = state?.pages?.size ?: 0
        val lastIndex = viewModel.currentPage.value ?: 0
        val completed = total > 0 && lastIndex >= total - 1
        sessionFlips = 0
        sessionStartMs = now
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).comicReadingStatsDao()
            val existing = dao.getByIllust(illustId) ?: ComicReadingStatsEntity().apply {
                this.illustId = illustId
                this.firstReadTime = now
                this.openCount = 0
            }
            existing.lastPageIndex = lastIndex
            existing.totalPageCount = if (total > 0) total else existing.totalPageCount
            existing.lastReadTime = now
            existing.totalDurationMs += durationMs
            existing.totalFlips += flips
            existing.openCount = (existing.openCount + 1).coerceAtLeast(1)
            existing.completed = if (completed) 1 else existing.completed
            dao.upsert(existing)
        }
    }

    private fun resolveIllustId(): Long {
        return arguments?.getLong(ARG_ILLUST_ID, 0L) ?: 0L
    }

    companion object {
        private const val ARG_ILLUST_ID = "illust_id"

        @JvmStatic
        fun newInstance(illustId: Long): ComicReaderV3Fragment = ComicReaderV3Fragment().apply {
            arguments = Bundle().apply { putLong(ARG_ILLUST_ID, illustId) }
        }
    }
}
