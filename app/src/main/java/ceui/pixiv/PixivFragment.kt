package ceui.pixiv

import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Illust
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.pushFragment
import ceui.pixiv.ui.IllustCardActionReceiver
import ceui.pixiv.ui.works.IllustFragmentArgs
import ceui.refactor.CommonAdapter
import ceui.refactor.ppppx
import com.scwang.smart.refresh.header.FalsifyFooter

open class PixivFragment(layoutId: Int) : Fragment(layoutId), IllustCardActionReceiver {

    override fun onClickIllustCard(illust: Illust) {
        pushFragment(
            R.id.navigation_illust,
            IllustFragmentArgs(illust.id).toBundle()
        )
    }
}

fun Fragment.setUpStaggerLayout(binding: FragmentHomeBinding, viewModel: PixivListViewModel<*, *>) {
    binding.refreshLayout.setOnRefreshListener {
        viewModel.refresh(RefreshHint.pullToRefresh())
    }

    val ctx = requireContext()

    viewModel.refreshState.observe(viewLifecycleOwner) { state ->
        if (state !is RefreshState.LOADING) {
            binding.refreshLayout.finishRefresh()
            binding.refreshLayout.finishLoadMore()
        }
        binding.emptyLayout.isVisible = state is RefreshState.LOADED && !state.hasContent
        if (state is RefreshState.LOADED) {
            if (state.hasNext) {
                binding.refreshLayout.setOnLoadMoreListener {
                    viewModel.loadMore()
                }
            } else {
                binding.refreshLayout.setRefreshFooter(FalsifyFooter(ctx))
            }
        }
        binding.progressCircular.isVisible = state is RefreshState.LOADING && state.refreshHint == RefreshHint.initialLoad()
    }
    binding.listView.addItemDecoration(SpacesItemDecoration(4.ppppx))
    val adapter = CommonAdapter(viewLifecycleOwner)
    binding.listView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
    binding.listView.adapter = adapter
    viewModel.holders.observe(viewLifecycleOwner) { holders ->
        adapter.submitList(holders)
    }
}