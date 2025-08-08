package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHome2ViewpagerBinding
import ceui.lisa.utils.Common
import ceui.loxia.RefreshState
import ceui.loxia.pushFragment
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.chats.MyChatsFragment
import ceui.pixiv.ui.circles.MyCirclesFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.discover.DiscoverFragment
import ceui.pixiv.ui.user.following.FollowingViewPagerFragment
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick

class Home2ViewPagerFragment : PixivFragment(R.layout.fragment_home_2_viewpager),
    ViewPagerFragment {
    private val binding by viewBinding(FragmentHome2ViewpagerBinding::bind)
    private val viewModel by viewModels<HomeViewPagerViewModel>()

    private var lastBackPressedTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeHeaderContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            binding.toolbar.updateLayoutParams {
                height = insets.top - 10.ppppx
            }
            binding.bottomTab.updatePadding(bottom = insets.bottom + 6.ppppx)
            windowInsets
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val currentItem = binding.homeViewPager.currentItem
                    if (currentItem != 0) {
                        binding.homeViewPager.setCurrentItem(0, false)
                        return
                    }

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressedTime < 2000) {
                        // 取消此 callback，让系统处理（退出）
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        lastBackPressedTime = currentTime
                        Common.showToast(getString(R.string.twice_back_will_close_app))
                    }
                }
            })

        val networkManager = requireNetworkStateManager()

        networkManager.refreshState.observe(viewLifecycleOwner) { state ->
            binding.networkStateLoading.isVisible = state is RefreshState.LOADING
        }

        binding.userIcon.setOnClick {
            pushFragment(R.id.navigation_mine_profile)
        }
        binding.naviSearch.setOnClick {
            pushFragment(R.id.navigation_search_all)
        }

        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())
            binding.homeHeaderContent.alpha = 1F - percentage
        }

        binding.account = SessionManager.loggedInAccount
        binding.viewModel = viewModel
        binding.iconDiscoverTab.setOnClick {
            binding.homeViewPager.setCurrentItem(0, false)
        }
        binding.iconCirclesTab.setOnClick {
            binding.homeViewPager.setCurrentItem(1, false)
        }
        binding.iconChatsTab.setOnClick {
            binding.homeViewPager.setCurrentItem(2, false)
        }
        binding.iconFriendsTab.setOnClick {
            binding.homeViewPager.setCurrentItem(3, false)
        }
        binding.homeViewPager.isUserInputEnabled = false
        binding.homeViewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.selectedTabIndex.value = position
            }
        })
        viewModel.selectedTabIndex.observe(viewLifecycleOwner) { index ->
            if (index == 1) {
                binding.tabName.text = "Circles"
            } else if (index == 2) {
                binding.tabName.text = "Square"
            } else if (index == 3) {
                binding.tabName.text = "Friends"
            }
        }
        binding.tabName.setOnClick {
            if (viewModel.selectedTabIndex.value == 0) {
                pushFragment(R.id.navigation_mine_profile)
            }
        }

        viewModel.composeButtonState.observe(viewLifecycleOwner) { state ->
            if (state == HomeViewPagerViewModel.ComposeButtonState.OPEN) {
                binding.homeCompose.animate()
                    .rotation(135f)
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(300L)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            } else {
                binding.homeCompose.animate()
                    .rotation(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300L)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
        }
        binding.homeCompose.setOnClick {
            viewModel.toggleComposeButton()
        }
        binding.homeViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 4
            }

            override fun createFragment(position: Int): Fragment {
                if (position == 0) {
                    return DiscoverFragment()
//                    return GLFragment()
                } else if (position == 1) {
                    return MyCirclesFragment()
                } else if (position == 2) {
                    return MyChatsFragment()
                } else {
                    return FollowingViewPagerFragment()
                }
            }
        }
    }
}