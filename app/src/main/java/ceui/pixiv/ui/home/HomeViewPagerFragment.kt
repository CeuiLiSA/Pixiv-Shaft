package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeViewpagerBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.rank.RankingIllustsFragment
import ceui.pixiv.ui.user.UserFollowingFragment
import ceui.pixiv.ui.user.UserFollowingFragmentArgs
import ceui.pixiv.ui.user.UserProfileFragmentArgs
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

class HomeViewPagerFragment : PixivFragment(R.layout.fragment_home_viewpager), ViewPagerFragment {
    private val binding by viewBinding(FragmentHomeViewpagerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbarLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            WindowInsetsCompat.CONSUMED
        }

        binding.userIcon.setOnClick {
            pushFragment(R.id.navigation_mine_profile)
        }

        SessionManager.loggedInAccount.observe(viewLifecycleOwner) { account ->
            Glide.with(this).load(GlideUrlChild(account.user?.profile_image_urls?.findMaxSizeUrl())).into(binding.userIcon)
            binding.userName.text = account.user?.name
        }

        binding.homeViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 3
            }

            override fun createFragment(position: Int): Fragment {
                if (position == 0) {
                    return HomeFragment()
                } else if (position == 1) {
                    return RankingIllustsFragment()
                } else {
                    return UserFollowingFragment().apply {
                        arguments = UserFollowingFragmentArgs(SessionManager.loggedInUid, "public").toBundle()
                    }
                }
            }
        }
    }
}