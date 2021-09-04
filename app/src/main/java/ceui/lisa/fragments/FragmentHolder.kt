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

        titles = if (data.userId == Shaft.sUserModel.user.id) {
            arrayOf("收藏", "其他")
        } else {
            arrayOf("插画", "其他")
        }

        val items = arrayOf<Fragment>(
            if (data.userId == Shaft.sUserModel.user.id) {
                FragmentLikeIllust.newInstance(data.userId, Params.TYPE_PUBLIC)
            } else {
                FragmentUserIllust.newInstance(data.userId, false)
            },
            FragmentUserRight()
        )
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
