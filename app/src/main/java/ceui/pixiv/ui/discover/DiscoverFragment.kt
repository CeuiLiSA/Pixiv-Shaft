package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentDiscoverBinding
import ceui.loxia.ObjectType
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.home.RecmdIllustMangaFragment
import ceui.pixiv.ui.home.RecmdIllustMangaFragmentArgs
import ceui.pixiv.ui.home.RecmdNovelFragment
import ceui.pixiv.widgets.setUpWith
import ceui.refactor.viewBinding

class DiscoverFragment : PixivFragment(R.layout.fragment_discover), HomeTabContainer {

    private val binding by viewBinding(FragmentDiscoverBinding::bind)
//    private val articlesViewModel by pixivValueViewModel {
//        Client.appApi.pixivsionArticles(Params.TYPE_ALL)
//    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        binding.articleBlock.setOnClickListener {
//            pushFragment(R.id.navigation_articles)
//        }
//
//        articlesViewModel.result.observe(viewLifecycleOwner) { resp ->
//            resp.displayList.getOrNull(0)?.thumbnail?.let { thumbnail ->
//                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview1)
//            }
//            resp.displayList.getOrNull(1)?.thumbnail?.let { thumbnail ->
//                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview2)
//            }
//            resp.displayList.getOrNull(2)?.thumbnail?.let { thumbnail ->
//                Glide.with(this).load(GlideUrlChild(thumbnail)).into(binding.articlePreview3)
//            }
//        }
        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        RecmdNovelFragment()
                    },
                    title = getString(R.string.type_novel)
                ),
                PagedFragmentItem(
                    builder = {
                        RecmdIllustMangaFragment().apply {
                            arguments = RecmdIllustMangaFragmentArgs(ObjectType.ILLUST).toBundle()
                        }
                    },
                    title = getString(R.string.type_illust)
                ),
                PagedFragmentItem(
                    builder = {
                        RecmdIllustMangaFragment().apply {
                            arguments = RecmdIllustMangaFragmentArgs(ObjectType.MANGA).toBundle()
                        }
                    },
                    title = getString(R.string.type_manga)
                ),

            ),
            this
        )
        binding.discoverViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.discoverViewPager, binding.slidingCursor, viewLifecycleOwner, {})
    }
}