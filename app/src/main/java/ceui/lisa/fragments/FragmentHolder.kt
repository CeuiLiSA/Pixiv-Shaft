@file:Suppress("DEPRECATION")

package ceui.lisa.fragments

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentHolderBinding
import ceui.lisa.utils.MyOnTabSelectedListener
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.observeEvent

class FragmentHolder : BaseFragment<FragmentHolderBinding>() {

    private lateinit var mUserViewModel: UserViewModel

    companion object {
        @JvmStatic
        fun newInstance(): FragmentHolder {
            return FragmentHolder()
        }
    }

    override fun initModel() {
        mUserViewModel = ViewModelProvider(mActivity).get(UserViewModel::class.java)
    }

    override fun initLayout() {
        mLayoutID = R.layout.fragment_holder
    }

    override fun initView() {
        val data = mUserViewModel.user.value ?: return

        val titles: Array<String>
        val items: Array<Fragment>

        when {
            data.userId == Shaft.sUserModel.user.id -> {
                titles = arrayOf(getString(R.string.userTab_collection), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        FragmentLikeIllust.newInstance(data.userId, Params.TYPE_PUBLIC),
                        FragmentUserRight()
                )
            }
            data.profile.total_manga > 0 -> {
                titles = arrayOf(getString(R.string.type_illust), getString(R.string.type_manga), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        FragmentUserIllust.newInstance(data.userId, false),
                        FragmentUserManga.newInstance(data.userId, false),
                        FragmentUserRight()
                )
            }
            else -> {
                titles = arrayOf(getString(R.string.type_illust), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        FragmentUserIllust.newInstance(data.userId, false),
                        FragmentUserRight()
                )
            }
        }
        mUserViewModel.refreshEvent.observeEvent(viewLifecycleOwner) {
            if (it > 0) {
                items.forEach { frag ->
                    if (frag is NetListFragment<*, *, *>) {
                        frag.refresh()
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        baseBind.viewPager.adapter = object : FragmentPagerAdapter(childFragmentManager) {

            override fun getItem(position: Int): Fragment {
                return items[position]
            }

            override fun getCount(): Int {
                return titles.size
            }

            override fun getPageTitle(position: Int): CharSequence {
                return titles[position]
            }
        }
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager)
        val listener = MyOnTabSelectedListener(items)
        baseBind.tabLayout.addOnTabSelectedListener(listener)
    }
}
