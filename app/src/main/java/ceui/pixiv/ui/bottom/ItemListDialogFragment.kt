package ceui.pixiv.ui.bottom

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.FragmentItemListDialogListDialogItemBinding
import ceui.lisa.databinding.FragmentItemListDialogListDialogBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.widgets.PixivBottomSheet
import ceui.pixiv.ui.common.viewBinding

// TODO: Customize parameter argument names
const val ARG_ITEM_COUNT = "item_count"

/**
 *
 * A fragment that shows a list of items as a modal bottom sheet.
 *
 * You can show this modal bottom sheet from your activity like this:
 * <pre>
 *    ItemListDialogFragment.newInstance(30).show(supportFragmentManager, "dialog")
 * </pre>
 */
class ItemListDialogFragment : PixivBottomSheet(R.layout.fragment_item_list_dialog_list_dialog), OffsetPageActionReceiver {

    private val binding by viewBinding(FragmentItemListDialogListDialogBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.list.layoutManager =
            LinearLayoutManager(context)
        binding.list.adapter = adapter
        arguments?.getInt(ARG_ITEM_COUNT)?.let {
            adapter.submitList(List(it) { index ->
                OffsetPageHolder(index, viewModel.choosenOffsetPage)
            })
        }
    }

    override fun onClickOffsetPage(index: Int) {
        dismissAllowingStateLoss()
    }
}

class OffsetPageHolder(val index: Int, val selectedIndex: LiveData<Int>) : ListItemHolder() {
    override fun getItemId(): Long {
        return index.toLong()
    }
}

@ItemHolder(OffsetPageHolder::class)
class OffsetPageViewHolder(bd: FragmentItemListDialogListDialogItemBinding) :
    ListItemViewHolder<FragmentItemListDialogListDialogItemBinding, OffsetPageHolder>(bd) {

    override fun onBindViewHolder(holder: OffsetPageHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.firstTitle.text = "第${holder.index + 1}页结果"
        binding.secondaryTitle.text = "第${holder.index * 30} ~ ${(holder.index + 1) * 30}个作品"
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<OffsetPageActionReceiver>()?.onClickOffsetPage(holder.index)
        }
    }
}

interface OffsetPageActionReceiver {
    fun onClickOffsetPage(index: Int)
}