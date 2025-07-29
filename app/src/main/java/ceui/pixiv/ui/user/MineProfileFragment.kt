package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.CommonViewPagerFragmentArgs
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.ViewPagerContentType
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.history.ViewHistoryFragmentArgs
import ceui.pixiv.utils.setOnClick

class MineProfileFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val vm by constructVM({ AppDatabase.getAppDatabase(requireContext()) }) { db ->
        MineProfileVM(db)
    }
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
        combineLatest(
            liveUser,
            vm.historyCount,
            vm.favoriteUserCount,
            viewModel.refreshState,
        ).observe(viewLifecycleOwner) { (user, historyCount, favoriteUserCount, state) ->
            if (user == null || historyCount == null || favoriteUserCount == null || state == null) {
                return@observe
            }

            if (state is RefreshState.LOADING) {
                return@observe
            }

            adapter.submitList(
                if (state !is RefreshState.LOADED) {
                    listOf(LoadingHolder(viewModel.refreshState) { viewModel.refresh(RefreshHint.ErrorRetry) })
                } else {
                    listOf()
                } + listOf(
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
                    TabCellHolder(
                        "特别关注",
                        extraInfo = "共${favoriteUserCount}条记录"
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_view_history,
                            ViewHistoryFragmentArgs(RecordType.FAVORITE_USER).toBundle()
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
                    TabCellHolder(
                        getString(R.string.browse_history),
                        extraInfo = "共${historyCount}条记录"
                    ).onItemClick {
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
                )
            )
        }
    }
}
