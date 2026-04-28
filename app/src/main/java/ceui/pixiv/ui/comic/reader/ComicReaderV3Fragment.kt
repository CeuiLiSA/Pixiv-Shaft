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
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentComicReaderV3Binding
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.loxia.ObjectPool
import ceui.lisa.models.IllustsBean
import kotlinx.coroutines.launch
import timber.log.Timber

class ComicReaderV3Fragment : Fragment(R.layout.fragment_comic_reader_v3) {

    private val binding by viewBinding(FragmentComicReaderV3Binding::bind)
    private val viewModel: ComicReaderV3ViewModel by viewModels {
        ComicReaderV3ViewModel.factory(resolveIllustId())
    }

    private lateinit var pagerAdapter: ComicPagerAdapter
    private lateinit var webtoonAdapter: ComicPagerAdapter

    private var chromeShown = true
    private var pendingResumeIndex: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyImmersiveAndBrightness()
        wireSystemInsets()
        wireTopBar()
        wireBottomBar()
        wireBackPress()

        pagerAdapter = ComicPagerAdapter(::toggleChrome).also { it.fillHeight = true }
        webtoonAdapter = ComicPagerAdapter(::toggleChrome).also { it.fillHeight = false }

        binding.comicPager.adapter = pagerAdapter
        binding.comicPager.offscreenPageLimit = ComicReaderSettings.preloadAhead.coerceAtLeast(1)
        binding.comicPager.layoutDirection = if (ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.RTL)
            View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
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

        viewModel.currentPage.observe(viewLifecycleOwner) { idx -> updateProgressUi(idx) }

        ComicReaderSettings.changes.observe(viewLifecycleOwner) { event ->
            when (event) {
                ComicReaderSettings.ChangeEvent.Layout -> applyReadingMode()
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
        binding.comicBottomBar.comicBtnPages.setOnClickListener { showPageJumpMenu() }
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
    }

    // ---- Menus --------------------------------------------------------------

    private fun showOverflowMenu() {
        showV3Menu {
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

    private fun showPageJumpMenu() {
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: 0
        if (total <= 0) {
            Toast.makeText(requireContext(), R.string.comic_reader_no_pages, Toast.LENGTH_SHORT).show(); return
        }
        val titles: Array<CharSequence> = (1..total).map {
            getString(R.string.comic_reader_jump_to_page, it) as CharSequence
        }.toTypedArray()
        ceui.lisa.utils.QMUIMenuPopup.show(requireContext(), binding.comicBottomBar.comicBtnPages, titles) { index, _ ->
            jumpToPage(index)
        }
    }

    // ---- Volume keys --------------------------------------------------------

    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!ComicReaderSettings.volumeKeyFlip) return false
        val total = (viewModel.loadState.value as? ComicReaderV3ViewModel.LoadState.Loaded)?.pages?.size ?: return false
        val current = viewModel.currentPage.value ?: 0
        val next = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) current + 1 else current - 1
        if (next < 0 || next >= total) return true
        jumpToPage(next)
        return true
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
