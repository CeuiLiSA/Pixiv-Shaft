package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.home.RecmdNovelDataSource
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.viewBinding
import kotlin.getValue

class ArtworkFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.illustId }) { illustId ->
        ArtworkViewModel(illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_MARGIN)
    }
}