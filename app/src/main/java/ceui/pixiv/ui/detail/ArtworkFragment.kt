package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecorationKt
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.clearItemDecorations
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.blocking.BlockingManager
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.ui.user.UserProfileFragment
import ceui.pixiv.ui.works.GalleryActionReceiver
import ceui.pixiv.ui.works.GalleryHolder
import ceui.pixiv.ui.works.PagedImgUrlFragmentArgs
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import timber.log.Timber
import kotlin.getValue

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment, GalleryActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.illustId }) { illustId ->
        ArtworkViewModel(illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liveIllust = ObjectPool.get<Illust>(safeArgs.illustId)
        combineLatest(BlockingManager.isWorkBlocked(safeArgs.illustId), liveIllust).observe(viewLifecycleOwner) { (isBlocked, illust) ->
            if (illust == null) {
                return@observe
            }

            binding.toolbarLayout.naviMore.setOnClick {
                showActionMenu {
                    add(
                        MenuItem("查看评论") {
                            pushFragment(
                                R.id.navigation_comments_illust, CommentsFragmentArgs(
                                    safeArgs.illustId, illust.user?.id ?: 0L,
                                    ObjectType.ILLUST
                                ).toBundle()
                            )
                        }
                    )
                    add(
                        MenuItem("举报作品") {

                        }
                    )
                    if (isBlocked == true) {
                        add(
                            MenuItem(getString(R.string.remove_blocking)) {
                                BlockingManager.removeBlockedWork(safeArgs.illustId)
                            }
                        )
                    } else {
                        add(
                            MenuItem(getString(R.string.add_blocking)) {
                                BlockingManager.addBlockedWork(safeArgs.illustId)
                            }
                        )
                    }
                }
            }

            setUpRefreshState(binding, viewModel, ListMode.CUSTOM)
            if (isBlocked == true) {
                binding.refreshLayout.isVisible = false
                binding.pageBackground.isVisible = false
                binding.dimmer.isVisible = false
            } else {
                binding.refreshLayout.isVisible = true
                binding.pageBackground.isVisible = true
                binding.dimmer.isVisible = true
                binding.listView.clearItemDecorations()
                binding.listView.addItemDecoration(LinearItemDecorationKt(16.ppppx, illust.page_count))
                val ctx = requireContext()
                binding.listView.layoutManager = LinearLayoutManager(ctx)
                blurBackground(binding, safeArgs.illustId)
            }
        }
    }

    override fun onClickGalleryHolder(index: Int, galleryHolder: GalleryHolder) {
        pushFragment(
            R.id.navigation_paged_img_urls,
            PagedImgUrlFragmentArgs(
                safeArgs.illustId,
                index
            ).toBundle()
        )
    }
}