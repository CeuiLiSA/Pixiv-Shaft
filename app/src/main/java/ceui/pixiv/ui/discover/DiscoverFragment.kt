package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentDiscoverBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectType
import ceui.loxia.pushFragment
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.PvisionMiniCardHolder
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.home.HomeFragment
import ceui.pixiv.ui.home.HomeFragmentArgs
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

class DiscoverFragment : PixivFragment(R.layout.fragment_discover), HomeTabContainer {

    private val binding by viewBinding(FragmentDiscoverBinding::bind)
    private val articlesViewModel by pixivValueViewModel {
        Client.appApi.pixivsionArticles(Params.TYPE_ALL)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.articleBlock.setOnClickListener {
            pushFragment(R.id.navigation_articles)
        }
        articlesViewModel.result.observe(viewLifecycleOwner) {
            it.displayList.getOrNull(0)?.thumbnail?.let { thumbnail ->
                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview1)
            }
            it.displayList.getOrNull(1)?.thumbnail?.let { thumbnail ->
                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview2)
            }
            it.displayList.getOrNull(2)?.thumbnail?.let { thumbnail ->
                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview3)
            }
        }
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    title = getString(R.string.type_illust)
                ),
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    title = getString(R.string.type_manga)
                ),
                PagedFragmentItem(
                    builder = {
                        HomeFragment().apply {
                            arguments = HomeFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    title = getString(R.string.type_novel)
                )
            ),
            this
        )
        binding.discoverViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.discoverViewPager, binding.slidingCursor, viewLifecycleOwner, {})
    }
}