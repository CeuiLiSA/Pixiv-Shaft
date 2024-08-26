package ceui.pixiv.widgets

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellMenuBinding
import ceui.lisa.databinding.DialogMenuBinding
import ceui.lisa.utils.Common
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.viewBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.util.UUID

open class MenuDialog : PixivDialog(R.layout.dialog_menu), MenuActionReceiver {

    private val args by navArgs<MenuDialogArgs>()
    private val task: CompletableDeferred<MenuItem>? get() {
        return viewModel.menuTaskPool[args.taskUuid]
    }

    private val binding by viewBinding(DialogMenuBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.menuList.layoutManager = LinearLayoutManager(requireContext())
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.menuList.adapter = adapter
        adapter.submitList(args.menuItems.map { menuItem ->
            MenuHolder(menuItem)
        })
    }

    override fun onClickMenu(menuItem: MenuItem) {
        task?.complete(menuItem)
        dismissAllowingStateLoss()
    }

    override fun performCancel() {
        super.performCancel()
        task?.cancel()
        viewModel.menuTaskPool.remove(args.taskUuid)
    }
}

@Parcelize
data class MenuItem(
    val title: String,
    val action: () -> Unit
) : Parcelable

class MenuHolder(val menuItem: MenuItem) : ListItemHolder() {

}

@ItemHolder(MenuHolder::class)
class MenuViewHolder(bd: CellMenuBinding) : ListItemViewHolder<CellMenuBinding, MenuHolder>(bd) {

    override fun onBindViewHolder(holder: MenuHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<MenuActionReceiver>()?.onClickMenu(holder.menuItem)
        }
    }
}

interface MenuActionReceiver {
    fun onClickMenu(menuItem: MenuItem)
}

fun Fragment.showActionMenu(builder: MutableList<MenuItem>.() -> Unit) {
    val dialogViewModel by activityViewModels<DialogViewModel>()
    val taskUUID = UUID.randomUUID().toString()
    val menuItems = mutableListOf<MenuItem>().apply(builder)
    val task = CompletableDeferred<MenuItem>()
    dialogViewModel.menuTaskPool[taskUUID] = task
    MenuDialog().apply {
        arguments = MenuDialogArgs(taskUUID, menuItems = menuItems.toTypedArray()).toBundle()
    }.show(childFragmentManager, "MenuDialog-${taskUUID}")
    MainScope().launch {
        val menuItem = task.await()
        menuItem.action()
        dialogViewModel.menuTaskPool.remove(taskUUID)
    }
}
