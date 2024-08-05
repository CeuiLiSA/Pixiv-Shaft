package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentUserProfileBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpToolbar
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayoutMediator

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
            resp
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpToolbar(binding.toolbarLayout)

        ObjectPool.get<User>(args.userId).observe(viewLifecycleOwner) { user ->
            if (user?.profile_image_urls?.findMaxSizeUrl()?.isNotEmpty() == true) {
                Glide.with(this).load(GlideUrlChild(user.profile_image_urls.findMaxSizeUrl())).into(binding.userIcon)
            }
            binding.follow.isVisible = user.is_followed != true
            binding.unfollow.isVisible = user.is_followed == true
            binding.follow.setOnClick {
                followUser(it, user.id.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick {
                unfollowUser(it, user.id.toInt())
            }
            binding.userName.text = user?.name
        }
        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result.profile?.background_image_url?.isNotEmpty() == true) {
                Glide.with(this).load(GlideUrlChild(result.profile.background_image_url)).into(binding.headerImage)
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
        TabLayoutMediator(binding.tabLayout, binding.viewPager
        ) { tab, position -> tab.setText("hello world") }.attach()
    }
}