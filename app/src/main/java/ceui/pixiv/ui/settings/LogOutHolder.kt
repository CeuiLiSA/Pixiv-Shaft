package ceui.pixiv.ui.settings

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellLogOutBinding
import ceui.loxia.ProgressIndicator
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.setOnClick


class LogOutHolder() : ListItemHolder() {
}

@ItemHolder(LogOutHolder::class)
class LogOutViewHolder(bd: CellLogOutBinding) : ListItemViewHolder<CellLogOutBinding, LogOutHolder>(bd) {

    override fun onBindViewHolder(holder: LogOutHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.logOut.setOnClick {
            it.findActionReceiverOrNull<LogOutActionReceiver>()?.onClickLogOut(it)
        }
    }
}

interface LogOutActionReceiver {
    fun onClickLogOut(sender: ProgressIndicator)
}