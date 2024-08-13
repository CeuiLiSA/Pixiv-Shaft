package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.constructVM
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

        binding.search.setOnClick {
            searchViewModel.triggerAllRefreshEvent()
        }


        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 3
            }

            override fun createFragment(position: Int): Fragment {
                if (position == 0) {
                    return SearchIlllustMangaFragment()
                } else if (position == 1) {
                    return SearchNovelFragment()
                } else {
                    return SearchUserFragment()
                }
            }
        }

        if (args.landingIndex > 0) {
            binding.viewPager.setCurrentItem(args.landingIndex, false)
        }
    }
}