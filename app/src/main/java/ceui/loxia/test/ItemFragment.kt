package ceui.loxia.test

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSlinkyListBinding
import ceui.loxia.SlinkyListFragment
import ceui.loxia.setUpSlinkyList
import ceui.loxia.slinkyListVMCustom
import ceui.refactor.*

/**
 * A fragment representing a list of Items.
 */
class ItemFragment : SlinkyListFragment(R.layout.fragment_slinky_list) {

    private val binding by viewBinding(FragmentSlinkyListBinding::bind)
    private val viewModel by slinkyListVMCustom {
        ItemRepository()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        setUpSlinkyList(binding.listView, binding.refreshLayout, binding.itemLoading, viewModel)
    }
}