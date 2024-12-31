package ceui.pixiv.ui.blocking

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.R
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.refactor.viewBinding

class BlockedItemListFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL)
        BlockingManager.blockedWorks.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list?.map { BlockedItemHolder(it) })
        }
    }
}