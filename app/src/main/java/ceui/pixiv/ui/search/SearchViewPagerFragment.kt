package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSearchViewpagerBinding
import ceui.loxia.Tag
import ceui.loxia.combineLatest
import ceui.loxia.hideKeyboard
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.widgets.DialogViewModel
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class SearchViewPagerFragment : TitledViewPagerFragment(R.layout.fragment_search_viewpager) {

    private val binding by viewBinding(FragmentSearchViewpagerBinding::bind)
    private val args by navArgs<SearchViewPagerFragmentArgs>()
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val searchViewModel by constructVM({ args.keyword }) { word ->
        SearchViewModel(word)
    }

    override fun onViewFirstCreated(view: View) {
        super.onViewFirstCreated(view)
        dialogViewModel.chosenUsersYoriCount.value = 0
        dialogViewModel.choosenOffsetPage.value = 0
        searchViewModel.illustSelectedRadioTabIndex.value = 0
        searchViewModel.novelSelectedRadioTabIndex.value = 0
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewModel = searchViewModel

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.searchLayout.updatePaddingRelative(top = insets.top)
            windowInsets
        }
        combineLatest(searchViewModel.tagList, searchViewModel.inputDraft).observe(viewLifecycleOwner) {
            val tags = it?.first ?: listOf()
            val inputing = it?.second ?: ""
            binding.search.isEnabled = tags.isNotEmpty() == true || inputing.isNotEmpty() == true
        }
        binding.search.setOnClick {
            commitEditingTag()
        }
        binding.tagEditer.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitEditingTag()
            }
            true
        }
        binding.tagsFlowView.setOnCellClickListener { cell, index ->

        }
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        SearchIlllustMangaFragment()
                    },
                    titleLiveData = getTitleLiveData(0).apply {
                        value = getString(R.string.string_136)
                    }
                ),
                PagedFragmentItem(
                    builder = {
                        SearchNovelFragment()
                    },
                    titleLiveData = getTitleLiveData(1).apply {
                        value = getString(R.string.type_novel)
                    }
                ),
                PagedFragmentItem(
                    builder = {
                        SearchUserFragment()
                    },
                    titleLiveData = getTitleLiveData(2).apply {
                        value = getString(R.string.type_user)
                    }
                )
            ),
            this
        )
        binding.searchViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.searchViewPager, binding.slidingCursor, viewLifecycleOwner, {})

        if (args.landingIndex > 0) {
            binding.searchViewPager.setCurrentItem(args.landingIndex, false)
        }
    }

    private fun commitEditingTag() {
        val draft = searchViewModel.inputDraft.value ?: ""
        if (draft.isNotEmpty()) {
            (searchViewModel.tagList.value ?: listOf()).toMutableList().also {
                it.add(Tag(draft))
                searchViewModel.tagList.value = it
                searchViewModel.inputDraft.value = ""
                binding.tagEditer.clearFocus()
                binding.root.requestFocus()
                hideKeyboard()
                searchViewModel.triggerAllRefreshEvent()
            }
        }
    }
}