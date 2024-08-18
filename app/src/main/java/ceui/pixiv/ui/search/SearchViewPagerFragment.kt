package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSearchViewpagerBinding
import ceui.loxia.ObjectType
import ceui.loxia.Tag
import ceui.loxia.combineLatest
import ceui.loxia.debounce
import ceui.loxia.hideKeyboard
import ceui.loxia.toDiff
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.trending.TrendingTagsFragment
import ceui.pixiv.ui.trending.TrendingTagsFragmentArgs
import ceui.pixiv.ui.user.recommend.RecommendUsersFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding

class SearchViewPagerFragment : PixivFragment(R.layout.fragment_search_viewpager), ViewPagerFragment {

    private val binding by viewBinding(FragmentSearchViewpagerBinding::bind)
    private val args by navArgs<SearchViewPagerFragmentArgs>()
    private val searchViewModel by constructVM({ args.keyword }) { word ->
        SearchViewModel(word)
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
                    title = getString(R.string.string_136)
                ),
                PagedFragmentItem(
                    builder = {
                        SearchNovelFragment()
                    },
                    title = getString(R.string.type_novel)
                ),
                PagedFragmentItem(
                    builder = {
                        SearchUserFragment()
                    },
                    title = getString(R.string.type_user)
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