package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.viewBinding

class CircleResultPreviewFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivValueViewModel(ownerProducer = { requireParentFragment() }, repositoryProducer = {
        RemoteRepository {
            Client.webApi.getCircleDetail("aa")
        }
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpLayoutManager(binding.listView, ListMode.STAGGERED_GRID)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        viewModel.result.observe(viewLifecycleOwner) { loadResult ->
            val resp = loadResult?.data ?: return@observe
            adapter.submitList(resp.body?.illusts?.map {
                IllustCardHolder(it.toIllust())
            })
        }
    }
}