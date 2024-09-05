package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentUserProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.ui.bottom.ItemListDialogFragment
import ceui.pixiv.ui.bottom.OffsetPageActionReceiver
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.common.ImgUrlFragment
import ceui.pixiv.ui.common.ImgUrlFragmentArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlin.math.roundToInt

interface UserActionReceiver {
    fun onClickUser(id: Long)
}

class UserProfileFragment : PixivFragment(R.layout.fragment_user_profile), ViewPagerFragment {

    private val binding by viewBinding(FragmentUserProfileBinding::bind)
    private val args by navArgs<UserProfileFragmentArgs>()
    private val viewModel by pixivValueViewModel(
        loader = {
            val resp = Client.appApi.getUserProfile(args.userId)
            resp.user?.let {
                ObjectPool.update(it)
            }
            ObjectPool.update(resp)
            resp
        }
    )

    private fun buildTabText(position: Int): String {
        val profile = viewModel.result.value?.profile
        if (profile != null) {
            if (position == 0) {
                return "发布插画(${profile.total_illusts})"
            } else if (position == 1) {
                return "发布漫画(${profile.total_manga})"
            }
        } else {
            if (position == 0) {
                return "发布插画"
            } else if (position == 1) {
                return "发布漫画"
            }
        }

        if (position == 2) {
            return "收藏插画"
        }
        return "hello world"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            windowInsets
        }




        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())

            if (percentage == 0f) {
                // AppBarLayout fully expanded
                Common.showLog("asdadsas onAppBarExpanded ${totalScrollRange}, ${verticalOffset}")
            } else if (percentage == 100f) {
                // AppBarLayout fully collapsed
                Common.showLog("asdadsas onAppBarCollapsed ${totalScrollRange}, ${verticalOffset}")
            } else {
                // AppBarLayout partially collapsed
                Common.showLog("asdadsas onAppBarPartiallyCollapsed ${totalScrollRange}, ${verticalOffset}, ${percentage}")
            }

            binding.infoLayout.alpha = 1F - percentage
        }

        val liveUser = ObjectPool.get<User>(args.userId)
        binding.user = liveUser
        liveUser.observe(viewLifecycleOwner) { user ->
            if (user?.profile_image_urls?.findMaxSizeUrl()?.isNotEmpty() == true) {
                binding.userIcon.setOnClick {
                    pushFragment(
                        R.id.navigation_img_url,
                        ImgUrlFragmentArgs(
                            user.profile_image_urls.findMaxSizeUrl() ?: "",
                            "user_${args.userId}_avatar.png"
                        ).toBundle()
                    )
                }
            }
            binding.follow.setOnClick {
                followUser(it, user.id.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick {
                unfollowUser(it, user.id.toInt())
            }
        }
        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result.profile?.background_image_url?.isNotEmpty() == true) {
                Glide.with(this).load(GlideUrlChild(result.profile.background_image_url))
                    .into(binding.headerImage)
                binding.headerImage.setOnClick {
                    pushFragment(
                        R.id.navigation_img_url,
                        ImgUrlFragmentArgs(
                            result.profile.background_image_url,
                            "user_${args.userId}_bg.png"
                        ).toBundle()
                    )
                }
            }
            binding.tabLayout.getTabAt(0)?.text = buildTabText(0)
            binding.tabLayout.getTabAt(1)?.text = buildTabText(1)
            binding.tabLayout.getTabAt(2)?.text = buildTabText(2)
            binding.naviMore.setOnClick {
                showActionMenu {
                    add(
                        MenuItem("下载全部作品", "实验性功能，测试中") {
                            FetchAllTask(taskFullName = "${ObjectPool.get<User>(args.userId).value?.name}创作的全部插画") {
                                Client.appApi.getUserCreatedIllusts(
                                    args.userId,
                                    ObjectType.ILLUST
                                )
                            }
                        }
                    )
                    add(
                        MenuItem("收藏全部作品", "实验性功能，测试中") {
                        }
                    )
                }
            }
        }

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 3
            }

            override fun createFragment(position: Int): Fragment {
                return if (position == 0) {
                    UserCreatedIllustsFragment().apply {
                        arguments = UserCreatedIllustsFragmentArgs(
                            userId = args.userId,
                            objectType = ObjectType.ILLUST
                        ).toBundle()
                    }
                } else if (position == 1) {
                    UserCreatedIllustsFragment().apply {
                        arguments = UserCreatedIllustsFragmentArgs(
                            userId = args.userId,
                            objectType = ObjectType.MANGA
                        ).toBundle()
                    }
                } else {
                    UserBookmarkedIllustsFragment().apply {
                        arguments = UserBookmarkedIllustsFragmentArgs(args.userId).toBundle()
                    }
                }
            }
        }
        TabLayoutMediator(
            binding.tabLayout, binding.viewPager
        ) { tab, position ->
            tab.setText(buildTabText(position))
        }.attach()
    }
}