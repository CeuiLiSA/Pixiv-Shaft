package ceui.loxia.test

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentItemList2Binding
import ceui.refactor.*

/**
 * A fragment representing a list of Items.
 */
class ItemFragment : Fragment(R.layout.fragment_item_list2) {

    private val binding by viewBinding(FragmentItemList2Binding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding.list) {
            val a = CommonAdapter(viewLifecycleOwner)
            layoutManager = LinearLayoutManager(context)
            adapter = a
            val list = mutableListOf<ListItemHolder>()
            for (index in 0..100) {
                if (index % 2 == 0) {
                    list.add(AAAAHolder(index.toString(), "我是AA第${index}个数据").onItemClick { sender ->
                    })
                } else {
                    list.add(BBBBHolder(index.toString(), "我是BB第${index}个数据").onItemClick { sender ->
                    })
                }
            }
            a.submitList(list)
        }
    }
}