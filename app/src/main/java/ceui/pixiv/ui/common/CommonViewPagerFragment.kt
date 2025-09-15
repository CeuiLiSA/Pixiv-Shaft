package ceui.pixiv.ui.common

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentCommonViewpagerBinding
import ceui.lisa.utils.Params
import ceui.loxia.ObjectType
import ceui.loxia.launchSuspend
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.blocking.BlockedItemListFragment
import ceui.pixiv.ui.blocking.BlockedItemListFragmentArgs
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.ViewPagerContentType.MyBlockingHistory
import ceui.pixiv.ui.common.ViewPagerContentType.MyViewHistory
import ceui.pixiv.ui.history.ViewHistoryFragment
import ceui.pixiv.ui.history.ViewHistoryFragmentArgs
import ceui.pixiv.ui.latest.LatestIllustMangaFragment
import ceui.pixiv.ui.latest.LatestIllustMangaFragmentArgs
import ceui.pixiv.ui.latest.LatestNovelFragment
import ceui.pixiv.ui.user.UserBookmarkedIllustsFragment
import ceui.pixiv.ui.user.UserBookmarkedIllustsFragmentArgs
import ceui.pixiv.ui.user.UserBookmarkedNovelFragment
import ceui.pixiv.ui.user.UserBookmarkedNovelFragmentArgs
import ceui.pixiv.ui.user.UserCreatedIllustsFragment
import ceui.pixiv.ui.user.UserCreatedIllustsFragmentArgs
import ceui.pixiv.ui.user.UserCreatedNovelFragment
import ceui.pixiv.ui.user.UserCreatedNovelFragmentArgs
import ceui.pixiv.ui.user.UserFollowingFragment
import ceui.pixiv.ui.user.UserFollowingFragmentArgs
import ceui.pixiv.widgets.setUpWith
import ceui.pixiv.widgets.setupVerticalAwareViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ViewPagerContentType {
    const val MyBookmarkIllustOrManga = 1
    const val MyBookmarkNovel = 2
    const val MyFollowingUsers = 3
    const val MyViewHistory = 4
    const val MyBlockingHistory = 5
    const val TheLatestPixivArtworks = 6
    const val CreatedByMeArtworks = 7
}

class CommonViewPagerViewModel : ViewModel() {
    private val titlesMap = hashMapOf<Int, MutableLiveData<String>>()

    fun getTitleLiveData(index: Int): MutableLiveData<String> {
        return titlesMap.getOrPut(index, defaultValue = {
            MutableLiveData<String>()
        })
    }
}

class CommonViewPagerFragment : TitledViewPagerFragment(R.layout.fragment_common_viewpager) {

    private val binding by viewBinding(FragmentCommonViewpagerBinding::bind)
    private val args by navArgs<CommonViewPagerFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupVerticalAwareViewPager2(binding.commonViewpager)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootLayout.updatePadding(0, insets.top, 0, 0)
            windowInsets
        }
        val pagedItems = mutableListOf<PagedFragmentItem>()
        if (args.contentType == ViewPagerContentType.MyBookmarkIllustOrManga) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserBookmarkedIllustsFragment().apply {
                            arguments = UserBookmarkedIllustsFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PUBLIC
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_391)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserBookmarkedIllustsFragment().apply {
                            arguments = UserBookmarkedIllustsFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PRIVATE
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_392)
                )
            )
        } else if (args.contentType == ViewPagerContentType.MyFollowingUsers) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserFollowingFragment().apply {
                            arguments = UserFollowingFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PUBLIC
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_391)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserFollowingFragment().apply {
                            arguments = UserFollowingFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PRIVATE
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_392)
                )
            )
        } else if (args.contentType == ViewPagerContentType.MyBookmarkNovel) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserBookmarkedNovelFragment().apply {
                            arguments = UserBookmarkedNovelFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PUBLIC
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_391)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserBookmarkedNovelFragment().apply {
                            arguments = UserBookmarkedNovelFragmentArgs(
                                SessionManager.loggedInUid,
                                Params.TYPE_PRIVATE
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_392)
                )
            )
        } else if (args.contentType == MyViewHistory) {
            val db = AppDatabase.getAppDatabase(requireContext())
            launchSuspend {
                withContext(Dispatchers.IO) {
                    val illustHistoryCount =
                        db.generalDao().getCountByRecordType(RecordType.VIEW_ILLUST_HISTORY)
                    val novelHistoryCount =
                        db.generalDao().getCountByRecordType(RecordType.VIEW_NOVEL_HISTORY)
                    val userHistoryCount =
                        db.generalDao().getCountByRecordType(RecordType.VIEW_USER_HISTORY)

                    withContext(Dispatchers.Main) {
                        getTitleLiveData(0).value =
                            "${getString(R.string.string_136)}(${illustHistoryCount})"
                        getTitleLiveData(1).value =
                            "${getString(R.string.type_novel)}(${novelHistoryCount})"
                        getTitleLiveData(2).value =
                            "${getString(R.string.type_user)}(${userHistoryCount})"
                    }
                }
            }

            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        ViewHistoryFragment().apply {
                            arguments = ViewHistoryFragmentArgs(
                                RecordType.VIEW_ILLUST_HISTORY
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_136)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        ViewHistoryFragment().apply {
                            arguments = ViewHistoryFragmentArgs(
                                RecordType.VIEW_NOVEL_HISTORY
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_novel)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        ViewHistoryFragment().apply {
                            arguments = ViewHistoryFragmentArgs(
                                RecordType.VIEW_USER_HISTORY
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_user)
                )
            )
        } else if (args.contentType == MyBlockingHistory) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        BlockedItemListFragment().apply {
                            arguments = BlockedItemListFragmentArgs(
                                RecordType.BLOCK_ILLUST
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.string_136)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        BlockedItemListFragment().apply {
                            arguments = BlockedItemListFragmentArgs(
                                RecordType.BLOCK_NOVEL
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_novel)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        BlockedItemListFragment().apply {
                            arguments = BlockedItemListFragmentArgs(
                                RecordType.BLOCK_USER
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_user)
                )
            )
        } else if (args.contentType == ViewPagerContentType.TheLatestPixivArtworks) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        LatestIllustMangaFragment().apply {
                            arguments = LatestIllustMangaFragmentArgs(
                                ObjectType.ILLUST
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_illust)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        LatestIllustMangaFragment().apply {
                            arguments = LatestIllustMangaFragmentArgs(
                                ObjectType.MANGA
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_manga)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        LatestNovelFragment()
                    },
                    initialTitle = getString(R.string.type_novel)
                )
            )
        } else if (args.contentType == ViewPagerContentType.CreatedByMeArtworks) {
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserCreatedIllustsFragment().apply {
                            arguments = UserCreatedIllustsFragmentArgs(
                                SessionManager.loggedInUid,
                                ObjectType.ILLUST
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_illust)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserCreatedIllustsFragment().apply {
                            arguments = UserCreatedIllustsFragmentArgs(
                                SessionManager.loggedInUid,
                                ObjectType.MANGA
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_manga)
                )
            )
            pagedItems.add(
                PagedFragmentItem(
                    builder = {
                        UserCreatedNovelFragment().apply {
                            arguments = UserCreatedNovelFragmentArgs(
                                SessionManager.loggedInUid
                            ).toBundle()
                        }
                    },
                    initialTitle = getString(R.string.type_novel)
                )
            )
        }
        val adapter = SmartFragmentPagerAdapter(pagedItems, this)
        binding.commonViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.commonViewpager,
            binding.slidingCursor,
            viewLifecycleOwner,
            {})
    }
}