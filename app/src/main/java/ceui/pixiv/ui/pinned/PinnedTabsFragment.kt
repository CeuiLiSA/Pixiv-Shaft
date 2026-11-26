package ceui.pixiv.ui.pinned

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPinnedContentBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.tintMenuIconsWhite
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import ceui.pixiv.witstudio.widget.WitLiquidGlassMenu
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「侧边栏 → 我置顶的内容」V3 宿主页（issue #1398 / #1399）。
 *
 * 三个 Tab：
 * - 0: 标签（[PinnedTagsFragment]，`search_table.pinned = 1`）
 * - 1: 作者（[PinnedUsersFragment]，`general_table.PINNED_USER`）
 * - 2: 搜索历史（[PinnedSearchHistoryFragment]，`search_table.pinned = 0` 全量本地历史）
 *
 * 统一提供：各 Tab 实时数量显示（搜索时显示「命中/总数」）、支持中/日/英的列表内搜索过滤、
 * 以及支持纯文本 / XML 的导出面板（[PinnedExportSheet]）。
 */
class PinnedTabsFragment : Fragment(R.layout.fragment_pinned_content) {

    private val binding by viewBinding(FragmentPinnedContentBinding::bind)
    private val viewModel: PinnedContentViewModel by viewModels()

    private val tabTitleRes = listOf(
        R.string.v3_label_tags,
        R.string.string_432,
        R.string.string_87,
    )

    private var mediator: TabLayoutMediator? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var searchDebounceJob: Job? = null
    private var suppressWatcher = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val palette = V3Palette.from(context)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.pinned_content)
        binding.toolbarTitle.typeface = context.v3Font(600)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.toolbar.inflateMenu(R.menu.pinned_content)
        binding.toolbar.tintMenuIconsWhite()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    PinnedExportSheet.show(this, viewModel, binding.viewPager.currentItem)
                    true
                }

                R.id.action_more -> {
                    val anchor = binding.toolbar.findViewById<View>(R.id.action_more) ?: binding.toolbar
                    showActionMenu(anchor)
                    true
                }

                else -> false
            }
        }

        binding.tabLayout.setSelectedTabIndicatorColor(palette.textAccent)
        binding.tabHairline.setBackgroundColor(palette.cardHairline)

        setupSearchBar(palette)
        setupPager()
        observeState()
    }

    private fun setupSearchBar(palette: V3Palette) {
        val context = requireContext()
        binding.searchBar.background = shape(
            radiusPx = context.dpF(24f),
            fill = palette.cardFill,
            stroke = palette.cardHairline,
            strokePx = context.hairlinePx(),
        )
        binding.searchInput.typeface = context.v3Font(500)
        binding.searchClear.background = context.ripple(null, shape(999f, Color.WHITE))

        // 宽屏列宽上限 720dp 居中，与下方三页列表卡片对齐。
        binding.searchContainer.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width <= 0) return@addOnLayoutChangeListener
            val side = PinnedListMetrics.sideInset(width, v.resources.displayMetrics.density)
            if (v.paddingLeft != side || v.paddingRight != side) {
                v.updatePadding(left = side, right = side)
            }
        }

        // 有搜索词时返回手势先清空搜索，不直接退出页面；无搜索词时放行系统预测式返回。
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearSearchAndBlur()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val initial = viewModel.searchQuery.value
        if (initial.isNotEmpty()) {
            suppressWatcher = true
            binding.searchInput.setText(initial)
            binding.searchInput.setSelection(initial.length)
            suppressWatcher = false
        }
        binding.searchClear.isVisible = initial.isNotEmpty()
        backCallback.isEnabled = initial.isNotEmpty()

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val text = s?.toString().orEmpty()
                binding.searchClear.isVisible = text.isNotEmpty()
                backCallback.isEnabled = text.isNotEmpty()
                searchDebounceJob?.cancel()
                if (text.isEmpty()) {
                    viewModel.setSearchQuery("")
                } else {
                    searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(SEARCH_DEBOUNCE_MS)
                        viewModel.setSearchQuery(text)
                    }
                }
            }
        })

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounceJob?.cancel()
                viewModel.setSearchQuery(binding.searchInput.text?.toString().orEmpty())
                activity?.let { Common.hideKeyboard(it) }
                true
            } else {
                false
            }
        }

        binding.searchClear.setOnClickListener {
            clearSearchAndBlur()
        }

        // 子 Tab 空态按钮「清除搜索」清空 VM 后，同步把输入框清干净。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchQuery.collect { query ->
                    if (query.isEmpty() && searchDebounceJob?.isActive != true && !binding.searchInput.text.isNullOrEmpty()) {
                        suppressWatcher = true
                        binding.searchInput.setText("")
                        suppressWatcher = false
                    }
                    val hasText = !binding.searchInput.text.isNullOrEmpty()
                    binding.searchClear.isVisible = hasText
                    backCallback.isEnabled = hasText
                }
            }
        }
    }

    private fun clearSearchAndBlur() {
        searchDebounceJob?.cancel()
        viewModel.clearSearchQuery()
        if (!binding.searchInput.text.isNullOrEmpty()) {
            suppressWatcher = true
            binding.searchInput.setText("")
            suppressWatcher = false
        }
        binding.searchInput.clearFocus()
        activity?.let { Common.hideKeyboard(it) }
    }

    private fun setupPager() {
        val pager = binding.viewPager
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PinnedContentViewModel.TAB_COUNT

            override fun createFragment(position: Int): Fragment = when (position) {
                PinnedContentViewModel.TAB_TAGS -> PinnedTagsFragment()
                PinnedContentViewModel.TAB_USERS -> PinnedUsersFragment()
                PinnedContentViewModel.TAB_HISTORY -> PinnedSearchHistoryFragment()
                else -> error("unreachable tab position: $position")
            }
        }
        pager.offscreenPageLimit = 2

        mediator = TabLayoutMediator(binding.tabLayout, pager) { tab, position ->
            tab.text = buildTabLabel(position, viewModel.tabCounts.value)
        }.also { it.attach() }

        updateSearchHint(pager.currentItem)
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateSearchHint(position)
            }
        }.also { pager.registerOnPageChangeCallback(it) }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 每次回到前台（用户从详情/作者/搜索页置顶或新增搜索后返回）刷新三份本地数据。
                viewModel.refreshAll()
                viewModel.tabCounts.collect { counts ->
                    for (pos in 0 until PinnedContentViewModel.TAB_COUNT) {
                        binding.tabLayout.getTabAt(pos)?.text = buildTabLabel(pos, counts)
                    }
                }
            }
        }
    }

    private fun updateSearchHint(position: Int) {
        val hintRes = when (position) {
            PinnedContentViewModel.TAB_TAGS -> R.string.pinned_search_hint_tags
            PinnedContentViewModel.TAB_USERS -> R.string.pinned_search_hint_users
            PinnedContentViewModel.TAB_HISTORY -> R.string.pinned_search_hint_history
            else -> R.string.pinned_search_hint_tags
        }
        binding.searchInput.setHint(hintRes)
    }

    private fun buildTabLabel(
        position: Int,
        counts: PinnedContentViewModel.TabCounts,
    ): CharSequence {
        val title = getString(tabTitleRes[position])
        if (!counts.hasLoaded) return title
        val tabCount = counts.forPosition(position)
        val countText = if (counts.isFiltering) {
            "${tabCount.matched}/${tabCount.total}"
        } else {
            tabCount.total.toString()
        }
        val numTypeface = requireContext().v3Font(600)
        return SpannableStringBuilder(title).apply {
            append(" ")
            val start = length
            append(countText)
            setSpan(RelativeSizeSpan(0.86f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TabularTypefaceSpan(numTypeface), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showActionMenu(anchor: View) {
        val position = binding.viewPager.currentItem
        val primary = Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
        WitLiquidGlassMenu.show(anchor) {
            borderless = true
            startPlatterColor = primary
            anchorIconRes = ceui.pixiv.witstudio.R.drawable.wit_ic_more_vert
            item(getString(R.string.pinned_export_action), R.drawable.ic_v3_export_24) {
                PinnedExportSheet.show(this@PinnedTabsFragment, viewModel, position)
            }
            item(getString(R.string.clear), R.drawable.ic_not_interested_black_24dp) {
                clearCurrentTab(position)
            }
        }
    }

    /** 按 FragmentStateAdapter 的默认 tag `f<itemId>` 取子页。 */
    private fun childTabAt(position: Int): Fragment? =
        childFragmentManager.findFragmentByTag("f$position")

    private fun clearCurrentTab(position: Int) {
        if (!viewModel.hasItemsInTab(position)) {
            Common.showToast(R.string.pinned_empty)
            return
        }
        when (position) {
            PinnedContentViewModel.TAB_TAGS ->
                (childTabAt(position) as? PinnedTagsFragment)?.showClearAllDialog()

            PinnedContentViewModel.TAB_USERS ->
                (childTabAt(position) as? PinnedUsersFragment)?.showClearAllDialog()

            PinnedContentViewModel.TAB_HISTORY ->
                (childTabAt(position) as? PinnedSearchHistoryFragment)?.showClearAllDialog()
        }
    }

    override fun onDestroyView() {
        searchDebounceJob?.cancel()
        searchDebounceJob = null
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        mediator?.detach()
        mediator = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    /** 为 Tab 计数套用 Montserrat 真实字重 + 等宽数字（`tnum`），数值刷新时不抖动。 */
    private class TabularTypefaceSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.typeface = tf
            tp.fontFeatureSettings = "tnum"
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.typeface = tf
            tp.fontFeatureSettings = "tnum"
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 120L
    }
}
