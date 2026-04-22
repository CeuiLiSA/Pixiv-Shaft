package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHistoryListBinding
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.viewBinding
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader

class FragmentHistoryUserList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val viewModel: HistoryUserViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
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

    private fun confirmDelete(entity: GeneralEntity) {
        val act = activity ?: return
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .setPositiveButton(R.string.string_141) { _, _ -> viewModel.delete(entity) }
            .setNegativeButton(R.string.string_142, null)
            .show()
    }
}
