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
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.FragmentUserBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.ui.chats.SeeMoreAction
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.blankj.utilcode.util.BarUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.scwang.smart.refresh.header.MaterialHeader
import jp.wasabeef.glide.transformations.BlurTransformation
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
        viewModel.userLiveData.observe(viewLifecycleOwner) { user ->
            binding.iconOfficial.isVisible = user.isOfficial()
            binding.iconVolunteer.isVisible = user.isVolunteer()
        }
        viewModel.userProfile.observe(viewLifecycleOwner) { profile ->
            binding.iconPrime.isVisible = profile.isPremium()
        }
        viewModel.blurBackground.observe(viewLifecycleOwner) { blurIllust ->
            val url = blurIllust?.image_urls?.large
            if (url?.isNotEmpty() == true) {
                Glide.with(this).load(GlideUrlChild(url))
                    .apply(bitmapTransform(BlurTransformation(15, 3))).transition(withCrossFade())
                    .into(binding.pageBackground)
            }
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