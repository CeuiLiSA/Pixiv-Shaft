package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.ObjectSpec
import ceui.lisa.view.LinearItemDecorationKt
import ceui.loxia.ObjectType
import ceui.loxia.clearItemDecorations
import ceui.loxia.combineLatest
import ceui.loxia.flag.FlagReasonFragmentArgs
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.ui.blocking.BlockingManager
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareIllust
import ceui.pixiv.ui.works.GalleryActionReceiver
import ceui.pixiv.ui.works.GalleryHolder
import ceui.pixiv.ui.works.PagedImgUrlFragmentArgs

import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.works.blurBackground
import kotlin.getValue

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment, GalleryActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({ Pair(safeArgs.illustId, requireActivity().lifecycleScope) }) { (illustId, lifecycleScope) ->
        ArtworkViewModel(illustId, lifecycleScope)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.CUSTOM)
        combineLatest(BlockingManager.isWorkBlocked(safeArgs.illustId), viewModel.illustLiveData).observe(viewLifecycleOwner) { (isBlocked, illust) ->
            if (illust == null) {
                return@observe
            }

            val ctx = requireContext()
            EntityWrapper.visitIllust(ctx, illust)

            if (isBlocked == true) {
                binding.refreshLayout.isVisible = false
                binding.pageBackground.isVisible = false
                binding.dimmer.isVisible = false
                binding.toolbarLayout.naviMore.setOnClick {
                    showActionMenu {
                        add(
                            MenuItem(getString(R.string.remove_blocking)) {
                                BlockingManager.removeBlockedWork(safeArgs.illustId)
                            }
                        )
                    }
                }
            } else {
                binding.refreshLayout.isVisible = true
                binding.pageBackground.isVisible = true
                binding.dimmer.isVisible = true
                binding.listView.clearItemDecorations()
                binding.listView.addItemDecoration(LinearItemDecorationKt(16.ppppx, illust.page_count))
                binding.listView.layoutManager = LinearLayoutManager(ctx)
                blurBackground(binding, safeArgs.illustId)
                binding.toolbarLayout.naviMore.setOnClick {
                    showActionMenu {
                        add(
                            MenuItem(getString(R.string.view_comments)) {
                                pushFragment(
                                    R.id.navigation_comments_illust, CommentsFragmentArgs(
                                        safeArgs.illustId, illust.user?.id ?: 0L,
                                        ObjectType.ILLUST
                                    ).toBundle()
                                )
                            }
                        )
                        add(
                            MenuItem(getString(R.string.string_110)) {
                                shareIllust(illust)
                            }
                        )
                        add(
                            MenuItem(getString(R.string.flag_artwork)) {
                                pushFragment(R.id.navigation_flag_reason, FlagReasonFragmentArgs(
                                    safeArgs.illustId, ObjectSpec.Illust).toBundle())
                            }
                        )
                        add(
                            MenuItem(getString(R.string.add_blocking)) {
                                BlockingManager.addBlockedWork(safeArgs.illustId)
                            }
                        )
                    }
                }
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