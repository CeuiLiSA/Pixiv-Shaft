package ceui.pixiv.widgets

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellMenuBinding
import ceui.lisa.databinding.DialogMenuBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.TokenGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

open class MenuDialog : PixivDialog(R.layout.dialog_menu), MenuActionReceiver {

    private val args by navArgs<MenuDialogArgs>()
    private val task: CompletableDeferred<MenuItem>?
        get() {
            return viewModel.menuTaskPool[args.taskUuid]
        }

    private val binding by viewBinding(DialogMenuBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.menuList.layoutManager = LinearLayoutManager(requireContext())
        binding.menuList.itemAnimator = null
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
    val secondaryTitle: String? = null,
    val action: () -> Unit
) : Parcelable

class MenuHolder(val menuItem: MenuItem) : ListItemHolder() {
    override fun getItemId(): Long {
        return menuItem.hashCode().toLong()
    }
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
    val token = TokenGenerator.generateToken()
    val menuItems = mutableListOf<MenuItem>().apply(builder)
    val task = CompletableDeferred<MenuItem>()
    dialogViewModel.menuTaskPool[token] = task
    MenuDialog().apply {
        arguments = MenuDialogArgs(token, menuItems = menuItems.toTypedArray()).toBundle()
    }.show(childFragmentManager, "MenuDialog-${token}")
    MainScope().launch {
        val menuItem = task.await()
        menuItem.action()
        dialogViewModel.menuTaskPool.remove(token)
    }
}
