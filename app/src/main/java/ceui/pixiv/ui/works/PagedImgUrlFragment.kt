package ceui.pixiv.ui.works

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedImgUrlBinding
import ceui.lisa.utils.Common
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ImgUrlFragment
import ceui.pixiv.ui.common.ImgUrlFragmentArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.setUpFullScreen
import ceui.pixiv.utils.setOnClick

interface PagedImgActionReceiver {
    fun onClickPagedImg()
}

class PagedImgUrlFragment : PixivFragment(R.layout.fragment_paged_img_url), PagedImgActionReceiver,
    ViewPagerFragment {

    private val args by navArgs<PagedImgUrlFragmentArgs>()
    private val viewModel by viewModels<ToggleToolnarViewModel>()
    private val viewPagerViewModel by viewModels<ViewPagerViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPagedImgUrlBinding.bind(view)

        setUpFullScreen(
            viewModel,
            listOf(
                binding.download,
                binding.toolbarLayout.root,
                binding.topShadow,
                binding.bottomShadow,
            ),
            binding.toolbarLayout
        )


        val illust = ObjectPool.get<Illust>(args.illustId).value ?: return
        binding.download.setOnClick {
            viewPagerViewModel.triggerDownloadEvent(binding.pagedViewpager.currentItem)
        }

        binding.pagedViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return illust.page_count
            }

            override fun createFragment(position: Int): Fragment {
                val url = if (illust.page_count == 1 && position == 0) {
                    illust.meta_single_page?.original_image_url ?: ""
                } else {
                    illust.meta_pages?.getOrNull(position)?.image_urls?.original ?: ""
                }
                return ImgUrlFragment().apply {
                    arguments = ImgUrlFragmentArgs(
                        url,
                        buildPixivWorksFileName(args.illustId, position)
                    ).toBundle()
                }
            }
        }
        binding.pagedViewpager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Common.showLog("sdaasdasdw ${position}")
                binding.toolbarLayout.naviTitle.text = "${position + 1}/${illust.page_count}"
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
            }
        })
        if (args.index > 0) {
            binding.pagedViewpager.setCurrentItem(args.index, false)
        }
    }

    override fun onClickPagedImg() {
        viewModel.toggleFullscreen()
    }
}