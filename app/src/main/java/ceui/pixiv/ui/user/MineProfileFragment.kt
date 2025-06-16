package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.CommonViewPagerFragmentArgs
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.ViewPagerContentType
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import com.blankj.utilcode.util.AppUtils

class MineProfileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivValueViewModel(
        repositoryProducer = {
            RemoteRepository {
                val resp = Client.appApi.getUserProfile(SessionManager.loggedInUid)
                resp.user?.let {
                    ObjectPool.update(it)
                }
                ObjectPool.update(resp)
                resp
            }
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_TABCELL)
        binding.toolbarLayout.naviMore.setOnClick {
            pushFragment(R.id.navigation_notification)
        }
        val liveUser = ObjectPool.get<User>(SessionManager.loggedInUid)
        liveUser.observe(viewLifecycleOwner) { user ->
            adapter.submitList(
                listOf(
                    MineHeaderHolder(liveUser).onItemClick {
                        pushFragment(
                            R.id.navigation_user,
                            UserFragmentArgs(user?.id ?: 0L).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.my_bookmarked_illusts)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.MyBookmarkIllustOrManga).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.my_bookmarked_novels)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.MyBookmarkNovel).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.string_321)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.MyFollowingUsers).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.string_322)).onItemClick {
                        pushFragment(
                            R.id.navigation_user_fans,
                            UserFansFragmentArgs(SessionManager.loggedInUid).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.the_latest_pixiv_artworks)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.TheLatestPixivArtworks).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.created_by_me_artworks)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.CreatedByMeArtworks).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.string_323)).onItemClick {
                        pushFragment(
                            R.id.navigation_user_friends,
                            UserFriendsFragmentArgs(SessionManager.loggedInUid).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.browse_history)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.MyViewHistory).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.blocking_list)).onItemClick {
                        pushFragment(
                            R.id.navigation_common_viewpager,
                            CommonViewPagerFragmentArgs(ViewPagerContentType.MyBlockingHistory).toBundle()
                        )
                    },
                    TabCellHolder(getString(R.string.created_tasks)).onItemClick {
                        pushFragment(
                            R.id.navigation_task_preview_list,
                        )
                    },
                    TabCellHolder(getString(R.string.action_settings)).onItemClick {
                        pushFragment(
                            R.id.navigation_settings,
                        )
                    },
                    TabCellHolder(getString(R.string.turn_off_v5_ui)).onItemClick {
                        launchSuspend {
                            if (alertYesOrCancel(getString(R.string.alert_when_turn_off_v5_ui))) {
                                Shaft.getMMKV().putBoolean(SessionManager.USE_NEW_UI_KEY, false)
                                AppUtils.relaunchApp()
                            }
                        }
                    },
                )
            )
        }
    }
}
