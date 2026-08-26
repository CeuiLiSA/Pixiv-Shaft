@file:Suppress("DEPRECATION")

package ceui.lisa.fragments

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentHolderBinding
import ceui.pixiv.session.SessionManager
import ceui.lisa.utils.MyOnTabSelectedListener
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.UserViewModel
import ceui.loxia.observeEvent
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.ui.collection.LikeIllustFeedFragment
import ceui.pixiv.ui.user.UserIllustFeedFragment

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
            data.user?.id == SessionManager.loggedInUid -> {
                titles = arrayOf(getString(R.string.userTab_collection), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        LikeIllustFeedFragment.newInstance(data.user?.id ?: 0L, Params.TYPE_PUBLIC),
                        FragmentUserRight()
                )
            }
            data.profile.total_manga > 0 -> {
                titles = arrayOf(getString(R.string.type_illust), getString(R.string.type_manga), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        UserIllustFeedFragment.newInstance(data.user?.id ?: 0L, false),
                        ceui.pixiv.ui.user.UserMangaFeedFragment.newInstance(data.user?.id ?: 0L, false),
                        FragmentUserRight()
                )
            }
            else -> {
                titles = arrayOf(getString(R.string.type_illust), getString(R.string.userTab_other))
                items = arrayOf<Fragment>(
                        UserIllustFeedFragment.newInstance(data.user?.id ?: 0L, false),
                        FragmentUserRight()
                )
            }
        }
        mUserViewModel.refreshEvent.observeEvent(viewLifecycleOwner) {
            if (it > 0) {
                items.forEach { frag ->
                    if (frag is FeedFragment) {
                        frag.forceRefresh()
                    }
                }
            }
        }
        // 必须 RESUME_ONLY_CURRENT：本 pager 的插画/漫画/收藏三个 tab 都是 feeds 版，靠 onResume
        // 懒加载（feedViewModels(autoLoad = false)）。单参构造 = BEHAVIOR_SET_USER_VISIBLE_HINT，
        // 那档 instantiateItem 只调 setUserVisibleHint(false)、不调 setMaxLifecycle，离屏 fragment
        // 照常跟宿主到 RESUMED → ensureLoaded 开火 → 用户从没点过漫画 tab 就先发一次
        // /v1/user/illusts?type=manga（offscreenPageLimit 默认 1，「有漫画」分支的漫画页在 index 1）。
        //
        // 同 pager 的 FragmentUserRight 虽是 legacy BaseLazyFragment 后裔，但它覆写 initData() 且不调
        // super，只从 activity-scoped VM 读数据填 view、零网络，根本没用 lazyData 钩子 —— 不受影响。
        // （反例见 FragmentCollection 的注释：真用 lazyData 的页在本档下 mUserVisibleHint 默认 true
        //  会导致开页即全量加载，那种页不能直接切。）
        @Suppress("DEPRECATION")
        baseBind.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager,
            BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT,
        ) {

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
