package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentUserProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.ImgUrlFragmentArgs
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.setUpWith
import ceui.pixiv.widgets.showActionMenu
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

interface UserActionReceiver {
    fun onClickUser(id: Long)
}

class UserProfileFragment : TitledViewPagerFragment(R.layout.fragment_user_profile) {

    private val binding by viewBinding(FragmentUserProfileBinding::bind)
    private val args by navArgs<UserProfileFragmentArgs>()
    private val viewModel by pixivValueViewModel(
        loader = {
            val resp = Client.appApi.getUserProfile(args.userId)
            resp.user?.let {
                ObjectPool.update(it)
            }
            ObjectPool.update(resp)
            resp
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            windowInsets
        }

        binding.appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) {
                return@addOnOffsetChangedListener
            }

            val percentage = (Math.abs(verticalOffset) / totalScrollRange.toFloat())
            binding.infoLayout.alpha = 1F - percentage
            binding.naviTitle.isVisible = (percentage == 1F)
        }

        val liveUser = ObjectPool.get<User>(args.userId)
        binding.user = liveUser
        liveUser.observe(viewLifecycleOwner) { user ->
            binding.naviTitle.text = user.name
            if (user?.profile_image_urls?.findMaxSizeUrl()?.isNotEmpty() == true) {
                binding.userIcon.setOnClick {
                    pushFragment(
                        R.id.navigation_img_url,
                        ImgUrlFragmentArgs(
                            user.profile_image_urls.findMaxSizeUrl() ?: "",
                            "user_${args.userId}_avatar.png"
                        ).toBundle()
                    )
                }
            }
            binding.follow.setOnClick {
                followUser(it, user.id.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick {
                unfollowUser(it, user.id.toInt())
            }
        }
        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result.profile?.background_image_url?.isNotEmpty() == true) {
                Glide.with(this).load(GlideUrlChild(result.profile.background_image_url))
                    .into(binding.headerImage)
                binding.headerImage.setOnClick {
                    pushFragment(
                        R.id.navigation_img_url,
                        ImgUrlFragmentArgs(
                            result.profile.background_image_url,
                            "user_${args.userId}_bg.png"
                        ).toBundle()
                    )
                }
            }
            getTitleLiveData(0).value = "发布插画(${result.profile?.total_illusts})"
            getTitleLiveData(1).value = "发布漫画(${result.profile?.total_manga})"
            getTitleLiveData(2).value = "发布小说(${result.profile?.total_novels})"
            binding.naviMore.setOnClick {
                showActionMenu {
                    add(
                        MenuItem("下载全部作品", "实验性功能，测试中") {
                            FetchAllTask(taskFullName = "下载${ObjectPool.get<User>(args.userId).value?.name}创作的全部插画", PixivTaskType.DownloadAll) {
                                Client.appApi.getUserCreatedIllusts(
                                    args.userId,
                                    ObjectType.ILLUST
                                )
                            }
                        }
                    )
                    add(
                        MenuItem("收藏全部作品", "实验性功能，测试中") {
                            FetchAllTask(taskFullName = "收藏${ObjectPool.get<User>(args.userId).value?.name}创作的全部插画", PixivTaskType.BookmarkAll) {
                                Client.appApi.getUserCreatedIllusts(
                                    args.userId,
                                    ObjectType.ILLUST
                                )
                            }
                        }
                    )
                }
            }
        }

        val adapter = SmartFragmentPagerAdapter(
            listOf(
                PagedFragmentItem(
                    builder = {
                        UserCreatedIllustsFragment().apply {
                            arguments = UserCreatedIllustsFragmentArgs(
                                userId = args.userId,
                                objectType = ObjectType.ILLUST
                            ).toBundle()
                        }
                    },
                    initialTitle = "发布插画"
                ),
                PagedFragmentItem(
                    builder = {
                        UserCreatedIllustsFragment().apply {
                            arguments = UserCreatedIllustsFragmentArgs(
                                userId = args.userId,
                                objectType = ObjectType.MANGA
                            ).toBundle()
                        }
                    },
                    initialTitle = "发布小说"
                ),
                PagedFragmentItem(
                    builder = {
                        UserCreatedNovelFragment().apply {
                            arguments = UserCreatedNovelFragmentArgs(
                                userId = args.userId,
                            ).toBundle()
                        }
                    },
                    initialTitle = "发布漫画"
                ),
                PagedFragmentItem(
                    builder = {
                        UserBookmarkedIllustsFragment().apply {
                            arguments = UserBookmarkedIllustsFragmentArgs(args.userId).toBundle()
                        }
                    },
                    initialTitle = "收藏插画"
                ),
            ),
            this
        )
        binding.profileViewPager.adapter = adapter
        binding.tabLayoutList.setUpWith(binding.profileViewPager, binding.slidingCursor, viewLifecycleOwner) {

        }
    }
}