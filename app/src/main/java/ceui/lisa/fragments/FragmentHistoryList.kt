package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.FragmentHistoryListBinding
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.viewBinding
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader

class FragmentHistoryList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }
    private val viewModel: HistoryListViewModel by viewModels { HistoryListViewModel.factory(historyType) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CommonAdapter(viewLifecycleOwner)

        val spanCount = if (historyType == TYPE_NOVEL) 1 else 2
        binding.recyclerView.layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            viewModel.loadFirst { binding.refreshLayout.finishRefresh() }
        }
        binding.refreshLayout.setOnLoadMoreListener {
            viewModel.loadMore { binding.refreshLayout.finishLoadMore() }
        }

        viewModel.setDeleteCallback { entity -> confirmDelete(entity) }
        viewModel.holders.observe(viewLifecycleOwner) { holders ->
            adapter.submitList(holders)
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.emptyLayout.isVisible = empty
        }

        if (viewModel.holders.value.isNullOrEmpty()) {
            viewModel.loadFirst()
        }
    }

    private fun confirmDelete(entity: IllustHistoryEntity) {
        val act = activity ?: return
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .setPositiveButton(R.string.string_141) { _, _ -> viewModel.delete(entity) }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }

    companion object {
        private const val ARG_TYPE = "history_type"
        private const val TYPE_NOVEL = 1

        fun newInstance(type: Int): FragmentHistoryList = FragmentHistoryList().apply {
            arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }
}
