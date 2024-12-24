package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecorationKt
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.works.blurBackground
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import kotlin.getValue

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.illustId }) { illustId ->
        ArtworkViewModel(illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        blurBackground(binding, safeArgs.illustId)
        val ctx = requireContext()
        binding.listView.layoutManager = LinearLayoutManager(ctx)
        val liveIllust = ObjectPool.get<Illust>(safeArgs.illustId)
        liveIllust.value?.let { illust ->
            binding.toolbarLayout.naviMore.setOnClick {
                pushFragment(
                    R.id.navigation_comments_illust, CommentsFragmentArgs(
                        safeArgs.illustId, illust.user?.id ?: 0L,
                        ObjectType.ILLUST
                    ).toBundle()
                )
            }
            binding.listView.addItemDecoration(LinearItemDecorationKt(16.ppppx, illust.page_count))
        }
        setUpRefreshState(binding, viewModel, ListMode.CUSTOM)
    }
}