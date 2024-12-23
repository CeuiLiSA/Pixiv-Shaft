package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Client
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class CircleResultPreviewFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivValueViewModel(ownerProducer = { requireParentFragment() }) {
        Client.webApi.getCircleDetail("aa")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpLayoutManager(binding.listView, ListMode.STAGGERED_GRID)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        viewModel.result.observe(viewLifecycleOwner) { resp ->
            adapter.submitList(resp.body?.illusts?.map {
                IllustCardHolder(it.toIllust())
            })
        }
    }
}