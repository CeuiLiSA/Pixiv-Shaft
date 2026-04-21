package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.database.IllustHistoryEntity
import ceui.pixiv.ui.common.viewBinding
import ceui.lisa.databinding.FragmentHistoryListBinding
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader

/**
 * A single-type history list (illust type=0, novel type=1).
 * Used as a child of [FragmentHistoryTabs].
 */
class FragmentHistoryList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }
    private val viewModel: HistoryListViewModel by viewModels { HistoryListViewModel.factory(historyType) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listAdapter = HistoryV3Adapter(
            context = requireContext(),
            items = viewModel.items.value?.toMutableList() ?: mutableListOf(),
            allIllustsProvider = { viewModel.illusts.value.orEmpty() },
            onRequestDelete = { _, entity -> confirmDelete(entity) },
        )

        val spanCount = if (historyType == TYPE_NOVEL) 1 else 2
        binding.recyclerView.layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = listAdapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            viewModel.loadFirst { binding.refreshLayout.finishRefresh() }
        }
        binding.refreshLayout.setOnLoadMoreListener {
            viewModel.loadMore { binding.refreshLayout.finishLoadMore() }
        }

        viewModel.items.observe(viewLifecycleOwner) { data ->
            listAdapter.submit(data.toMutableList())
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.emptyLayout.isVisible = empty
        }

        if (viewModel.items.value.isNullOrEmpty()) {
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
