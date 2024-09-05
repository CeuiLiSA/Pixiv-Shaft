package ceui.pixiv.ui.bottom

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.marginTop
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.DialogAlertBinding
import ceui.lisa.databinding.FragmentItemListDialogListDialogItemBinding
import ceui.lisa.databinding.FragmentItemListDialogListDialogBinding
import ceui.lisa.utils.Common
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.setUpFullScreen
import ceui.pixiv.widgets.PixivBottomSheet
import ceui.refactor.ppppx
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.internal.ViewUtils.doOnApplyWindowInsets
import com.google.android.material.internal.WindowUtils

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
            val holders = mutableListOf<ListItemHolder>()
            repeat(it) { index ->
                holders.add(OffsetPageHolder(index, viewModel.choosenOffsetPage))
            }
            adapter.submitList(holders)
        }
    }

    companion object {

        // TODO: Customize parameters
        fun newInstance(itemCount: Int): ItemListDialogFragment =
            ItemListDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_ITEM_COUNT, itemCount)
                }
            }

    }

    override fun onClickOffsetPage(index: Int) {
        viewModel.choosenOffsetPage.value = index
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