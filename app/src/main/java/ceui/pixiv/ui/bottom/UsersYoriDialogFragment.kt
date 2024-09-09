package ceui.pixiv.ui.bottom

import android.os.Bundle
import android.view.View
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUsersYoruItemBinding
import ceui.lisa.databinding.FragmentItemListDialogListDialogBinding
import ceui.loxia.Event
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.widgets.PixivBottomSheet
import ceui.refactor.viewBinding

class UsersYoriDialogFragment : PixivBottomSheet(R.layout.fragment_item_list_dialog_list_dialog),
    UsersYoriActionReceiver {

    private val binding by viewBinding(FragmentItemListDialogListDialogBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.list.layoutManager =
            LinearLayoutManager(context)
        binding.list.adapter = adapter
        adapter.submitList(ITEMS.map { count ->
            UsersYoriHolder(count, viewModel.chosenUsersYoriCount)
        })
    }

    companion object {
        private val ITEMS = listOf(
            0,
            100,
            500,
            1000,
            5000,
            10000,
            20000,
            30000,
            50000,
            100000,
        )
    }

    override fun onClickUsersYori(count: Int) {
        viewModel.chosenUsersYoriCount.value = count
        viewModel.triggerUsersYoriEvent.value = Event(System.currentTimeMillis())
        dismissAllowingStateLoss()
    }
}


class UsersYoriHolder(val count: Int, val selectedCount: LiveData<Int>) : ListItemHolder() {
    override fun getItemId(): Long {
        return count.toLong()
    }
}

@ItemHolder(UsersYoriHolder::class)
class UsersYoriViewHolder(bd: CellUsersYoruItemBinding) :
    ListItemViewHolder<CellUsersYoruItemBinding, UsersYoriHolder>(bd) {

    override fun onBindViewHolder(holder: UsersYoriHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        if (holder.count == 0) {
            binding.firstTitle.text = context.getString(R.string.not_selected)
        } else {
            binding.firstTitle.text = "${holder.count}users入り"
        }
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<UsersYoriActionReceiver>()?.onClickUsersYori(holder.count)
        }
    }
}

interface UsersYoriActionReceiver {
    fun onClickUsersYori(count: Int)
}