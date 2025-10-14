package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentUserBinding
import ceui.lisa.utils.Params
import ceui.loxia.ObjectType
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.chats.SeeMoreAction
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import com.blankj.utilcode.util.BarUtils
import com.scwang.smart.refresh.header.MaterialHeader
import timber.log.Timber

class UserFragment : PixivFragment(R.layout.fragment_user), ViewPagerFragment, SeeMoreAction,
    FitsSystemWindowFragment {

    private val safeArgs by navArgs<UserFragmentArgs>()
    private val binding by viewBinding(FragmentUserBinding::bind)
    private val viewModel by constructVM({ safeArgs.userId }) { userId ->
        Timber.d("userId-${userId}")
        UserViewModel(userId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            binding.headerContent.updatePaddingRelative(top = insets.top + BarUtils.getActionBarHeight())
            windowInsets
        }

        val context = requireContext()

        val isNowFavoriteLiveData = AppDatabase.getAppDatabase(context).generalDao()
            .isUserNowFavorite(RecordType.FAVORITE_USER, safeArgs.userId)

        val userBlockedLiveData = AppDatabase.getAppDatabase(context).generalDao()
            .isObjectBlocked(RecordType.BLOCK_USER, safeArgs.userId)

        binding.userBlocked = userBlockedLiveData

        combineLatest(
            viewModel.userLiveData,
            isNowFavoriteLiveData,
            userBlockedLiveData,
        ).observe(viewLifecycleOwner) { (user, isNowFavorite, isUserBlocked) ->
            if (user == null) {
                return@observe
            }

            runOnceWithinFragmentLifecycle("visit-user-${safeArgs.userId}") {
                requireEntityWrapper().visitUser(context, user)
            }
            binding.iconOfficial.isVisible = user.isOfficial()
            binding.iconVolunteer.isVisible = user.isVolunteer()


            binding.unblockUser.setOnClick {
                requireEntityWrapper().unblockUser(context, user)
            }

            binding.naviMore.setOnClick {
                showActionMenu {
                    if (isNowFavorite == true) {
                        add(MenuItem("移除特别关注") {
                            requireEntityWrapper().removeFavoriteUser(context, user)
                        })
                    } else {
                        add(MenuItem("添加到特别关注") {
                            requireEntityWrapper().addUserToFavorite(context, user)
                        })
                    }

                    if (isUserBlocked == true) {
                        add(MenuItem("取消屏蔽此作者") {
                            requireEntityWrapper().unblockUser(context, user)
                        })
                    } else {
                        add(MenuItem("屏蔽此作者的内容") {
                            requireEntityWrapper().blockUser(context, user)
                        })

                    }
                }
            }
        }
        viewModel.userProfile.observe(viewLifecycleOwner) { profile ->
            binding.iconPrime.isVisible = profile.isPremium()
        }
//        viewModel.blurBackground.observe(viewLifecycleOwner) { blurIllust ->
//            val url = blurIllust?.image_urls?.large
//            if (url?.isNotEmpty() == true) {
//                Glide.with(this).load(GlideUrlChild(url))
//                    .apply(bitmapTransform(BlurTransformation(15, 3))).transition(withCrossFade())
//                    .into(binding.pageBackground)
//            }
//        }
        binding.followingLayout.setOnClick {
            pushFragment(
                R.id.navigation_user_following_list, UserFollowingFragmentArgs(
                    safeArgs.userId,
                    Params.TYPE_PUBLIC
                ).toBundle()
            )
        }
        binding.followersLayout.setOnClick {
            pushFragment(
                R.id.navigation_user_fans,
                UserFansFragmentArgs(safeArgs.userId).toBundle()
            )
        }
        binding.postFollow.setOnClick {
            followUser(it, safeArgs.userId.toInt(), Params.TYPE_PUBLIC)
        }
        binding.removeFollow.setOnClick {
            unfollowUser(it, safeArgs.userId.toInt())
        }
        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            viewModel.refresh(RefreshHint.PullToRefresh)
        }
        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            if (state is RefreshState.LOADED || state is RefreshState.ERROR) {
                binding.refreshLayout.finishRefresh()
            } else {
            }
        }
        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            binding.refreshLayout.isEnabled = verticalOffset == 0
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())
            binding.headerContent.alpha = 1F - percentage
            binding.naviTitle.isVisible = (percentage == 1F)
        }
        binding.user = viewModel.userLiveData
        binding.profile = viewModel.userProfile
        binding.userViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 1
            }

            override fun createFragment(position: Int): Fragment {
                return UserContentFragment()
            }
        }
    }

    override fun seeMore(type: Int) {
        if (type == SeeMoreType.USER_CREATED_ILLUST) {
            pushFragment(
                R.id.navigation_user_created_illust, UserCreatedIllustsFragmentArgs(
                    userId = safeArgs.userId, objectType = ObjectType.ILLUST
                ).toBundle()
            )
        } else if (type == SeeMoreType.USER_CREATED_MANGA) {
            pushFragment(
                R.id.navigation_user_created_illust, UserCreatedIllustsFragmentArgs(
                    userId = safeArgs.userId, objectType = ObjectType.MANGA
                ).toBundle()
            )
        } else if (type == SeeMoreType.USER_BOOKMARKED_ILLUST) {
            pushFragment(
                R.id.navigation_user_bookmarked_illust, UserBookmarkedIllustsFragmentArgs(
                    safeArgs.userId, Params.TYPE_PUBLIC
                ).toBundle()
            )
        } else if (type == SeeMoreType.USER_CREATED_NOVEL) {
            pushFragment(
                R.id.navigation_user_created_novel, UserCreatedNovelFragmentArgs(
                    safeArgs.userId
                ).toBundle()
            )
        }
    }
}