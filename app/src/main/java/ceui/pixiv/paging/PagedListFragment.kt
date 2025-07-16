package ceui.pixiv.paging

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PagedListFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by viewModels<ArticleViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = CommonPagingAdapter(viewLifecycleOwner)

        val headerAdapter = LoadingStateAdapter { adapter.retry() }
        val footerAdapter = LoadingStateAdapter { adapter.retry() }

        val concatAdapter = adapter.withLoadStateHeaderAndFooter(
            header = headerAdapter,
            footer = footerAdapter
        )

        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.adapter = concatAdapter

        // Paging数据收集
        lifecycleScope.launch {
            viewModel.pager.collectLatest {
                adapter.submitData(it)
            }
        }

        // 下拉刷新
        binding.refreshLayout.setOnRefreshListener {
            adapter.refresh()
        }

        // 停止刷新动画
        lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest { loadStates ->
                binding.refreshLayout.isRefreshing = loadStates.refresh is LoadState.Loading
            }
        }
    }
}
