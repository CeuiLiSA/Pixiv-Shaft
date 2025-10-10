package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.LayoutBookmarkWidgetBinding
import ceui.lisa.models.ObjectSpec
import ceui.lisa.view.LinearItemDecorationKt
import ceui.loxia.ObjectType
import ceui.loxia.clearItemDecorations
import ceui.loxia.combineLatest
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.flag.FlagReasonFragmentArgs
import ceui.loxia.pushFragment
import ceui.loxia.requireEntityWrapper
import ceui.loxia.requireTaskPool
import ceui.loxia.threadSafeArgs
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.chats.SeeMoreAction
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareIllust
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.related.RelatedIllustsFragmentArgs
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.works.GalleryActionReceiver
import ceui.pixiv.ui.works.GalleryHolder
import ceui.pixiv.ui.works.PagedImgUrlFragmentArgs
import ceui.pixiv.ui.works.buildPixivWorksFileName
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    GalleryActionReceiver, SeeMoreAction {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({
        safeArgs.illustId to requireTaskPool()
    }) { (illustId, taskPool) ->
        ArtworkViewModel(illustId, taskPool)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.CUSTOM)
        val ctx = requireContext()

        val isBlockedLiveData = AppDatabase.getAppDatabase(ctx).generalDao()
            .isObjectBlocked(RecordType.BLOCK_ILLUST, safeArgs.illustId)

        val userBlockedLiveData = AppDatabase.getAppDatabase(context).generalDao().isObjectBlocked(
            RecordType.BLOCK_USER, viewModel.illustLiveData.value?.user?.id ?: 0L
        )

        binding.listView.layoutManager = LinearLayoutManager(ctx)
        val bookmarkView = setUpBookmarkButton()
        val taskPool = requireTaskPool()

        combineLatest(
            isBlockedLiveData,
            viewModel.illustLiveData,
            userBlockedLiveData,
        ).observe(viewLifecycleOwner) { (isIllustBlocked, illust, isUserBlocked) ->
            if (isIllustBlocked == null || illust == null || isUserBlocked == null) {
                return@observe
            }

            val isBlocked = isIllustBlocked || isUserBlocked

            bookmarkView.isVisible = !isBlocked
            if (isBlocked || isUserBlocked) {
                binding.refreshLayout.isVisible = false
                binding.toolbarLayout.naviMore.setOnClick {
                    showActionMenu {
                        if (isIllustBlocked) {
                            add(
                                MenuItem(getString(R.string.remove_blocking)) {
                                    requireEntityWrapper().unblockIllust(ctx, illust)
                                })
                        }
                        if (isUserBlocked) {
                            illust.user?.let { user ->
                                add(
                                    MenuItem(getString(R.string.remove_user_blocking)) {
                                        requireEntityWrapper().unblockUser(ctx, user)
                                    })
                            }
                        }
                    }
                }
            } else {
                runOnceWithinFragmentLifecycle("visit-illust-${safeArgs.illustId}") {
                    requireEntityWrapper().visitIllust(ctx, illust)
                }

                binding.refreshLayout.isVisible = true
                binding.listView.clearItemDecorations()
                binding.listView.addItemDecoration(
                    LinearItemDecorationKt(
                        16.ppppx, illust.page_count
                    )
                )

                if (illust.isAuthurExist()) {
                    binding.toolbarLayout.naviMore.isVisible = true
                    binding.toolbarLayout.naviMore.setOnClick {
                        showActionMenu {
                            add(
                                MenuItem(getString(R.string.string_7)) {
                                    when {
                                        illust.page_count == 1 -> {
                                            // Single page handling
                                            val imageUrl =
                                                illust.meta_single_page?.original_image_url ?: ""
                                            val downloadTask = taskPool.getDownloadTask(
                                                NamedUrl(
                                                    buildPixivWorksFileName(illust.id, 0), imageUrl
                                                ), requireActivity().lifecycleScope
                                            )
                                            downloadTask.start { }
                                        }

                                        !illust.meta_pages.isNullOrEmpty() -> {
                                            // Multiple pages handling
                                            illust.meta_pages.mapIndexed { index, metaPage ->
                                                val imageUrl = metaPage.image_urls?.original ?: ""
                                                val downloadTask = taskPool.getDownloadTask(
                                                    NamedUrl(
                                                        buildPixivWorksFileName(illust.id, index),
                                                        imageUrl
                                                    ), requireActivity().lifecycleScope
                                                )
                                                downloadTask.start { }
                                            }
                                        }

                                        else -> {

                                        }
                                    }
                                }
                            )
                            add(
                                MenuItem(getString(R.string.view_comments)) {
                                    pushFragment(
                                        R.id.navigation_comments_illust, CommentsFragmentArgs(
                                            safeArgs.illustId,
                                            illust.user?.id ?: 0L,
                                            ObjectType.ILLUST
                                        ).toBundle()
                                    )
                                })
                            add(
                                MenuItem(getString(R.string.string_110)) {
                                    shareIllust(illust)
                                })
                            add(
                                MenuItem(getString(R.string.flag_artwork)) {
                                    pushFragment(
                                        R.id.navigation_flag_reason, FlagReasonFragmentArgs(
                                            safeArgs.illustId, ObjectSpec.JAVA_ILLUST
                                        ).toBundle()
                                    )
                                })
                            add(
                                MenuItem(getString(R.string.add_blocking)) {
                                    requireEntityWrapper().blockIllust(ctx, illust)
                                })
                        }
                    }
                } else {
                    binding.toolbarLayout.naviMore.isVisible = false
                }
            }
        }
    }

    private fun setUpBookmarkButton(): View {
        val bookmarkWidget =
            LayoutBookmarkWidgetBinding.inflate(LayoutInflater.from(requireContext()))

        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            marginEnd = 16.ppppx // 扩展属性或者手动转 px
            bottomMargin = 40.ppppx
        }

        bookmarkWidget.root.layoutParams = params
        binding.root.addView(bookmarkWidget.root)

        bookmarkWidget.lifecycleOwner = viewLifecycleOwner
        bookmarkWidget.illust = viewModel.illustLiveData
        bookmarkWidget.bookmark.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(it, safeArgs.illustId)
        }

        return bookmarkWidget.root
    }

    override fun onClickGalleryHolder(index: Int, galleryHolder: GalleryHolder) {
        pushFragment(
            R.id.navigation_paged_img_urls, PagedImgUrlFragmentArgs(
                safeArgs.illustId, index
            ).toBundle()
        )
    }

    override fun seeMore(type: Int) {
        if (type == SeeMoreType.RELATED_ILLUST) {
            pushFragment(
                R.id.navigation_related_illusts,
                RelatedIllustsFragmentArgs(safeArgs.illustId).toBundle()
            )
        }
    }
}