package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeViewpagerBinding
import ceui.pixiv.PixivFragment
import ceui.pixiv.ViewPagerFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.rank.RankingIllustsFragment
import ceui.pixiv.ui.user.UserFollowingFragment
import ceui.pixiv.ui.user.UserFollowingFragmentArgs
import ceui.refactor.viewBinding
import com.google.android.material.transition.platform.MaterialFadeThrough
import com.google.android.material.transition.platform.MaterialSharedAxis

class HomeViewPagerFragment : PixivFragment(R.layout.fragment_home_viewpager), ViewPagerFragment {
    private val binding by viewBinding(FragmentHomeViewpagerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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