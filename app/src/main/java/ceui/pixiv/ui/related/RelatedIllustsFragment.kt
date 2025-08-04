package ceui.pixiv.ui.related

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.PagingIllustAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class RelatedIllustsFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<RelatedIllustsFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.illustId }) { illustId ->
        PagingIllustAPIRepository {
            Client.appApi.getRelatedIllusts(illustId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel)
    }
}