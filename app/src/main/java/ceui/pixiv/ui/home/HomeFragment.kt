package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Illust
import ceui.loxia.RefreshState
import ceui.loxia.pushFragment
import ceui.pixiv.PixivFragment
import ceui.pixiv.ui.IllustCardActionReceiver
import ceui.pixiv.ui.IllustCardHolder
import ceui.pixiv.ui.works.IllustFragmentArgs
import ceui.refactor.CommonAdapter
import ceui.refactor.ppppx
import ceui.refactor.viewBinding
import com.google.android.material.transition.platform.MaterialFadeThrough
import com.google.android.material.transition.platform.MaterialSharedAxis

class HomeFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by viewModels<HomeViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.refreshLayout.setOnRefreshListener {
            viewModel.refresh()
        }
        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            if (state !is RefreshState.LOADING) {
                binding.refreshLayout.finishRefresh()
            }
        }
        binding.listView.addItemDecoration(SpacesItemDecoration(4.ppppx))
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.listView.adapter = adapter
        viewModel.obj.observe(viewLifecycleOwner) { obj ->
            adapter.submitList(
                obj.illusts.map { illust ->
                    IllustCardHolder(illust)
                }
            )
        }
    }
}