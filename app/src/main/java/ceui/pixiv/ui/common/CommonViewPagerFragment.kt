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
import ceui.lisa.databinding.FragmentCommonViewpagerBinding
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.user.UserBookmarkedIllustsFragment
import ceui.pixiv.ui.user.UserBookmarkedIllustsFragmentArgs
import ceui.pixiv.ui.user.UserBookmarkedNovelFragment
import ceui.pixiv.ui.user.UserBookmarkedNovelFragmentArgs
import ceui.pixiv.ui.user.UserFollowingFragment
import ceui.pixiv.ui.user.UserFollowingFragmentArgs
import ceui.pixiv.widgets.setUpWith

object ViewPagerContentType {
    const val MyBookmarkIllustOrManga = 1
    const val MyBookmarkNovel = 2
    const val MyFollowingUsers = 3
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