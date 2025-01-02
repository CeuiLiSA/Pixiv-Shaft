package ceui.loxia.flag

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick


class FlagReasonHolder(val id: Int, val content: String, val key: String) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? FlagReasonHolder)?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? FlagReasonHolder)?.id && content == (other as? FlagReasonHolder)?.content && key == (other as? FlagReasonHolder)?.key
    }
}

@ItemHolder(FlagReasonHolder::class)
class FlagReasonViewHolder(binding: CellFlagReasonBinding) :
    ListItemViewHolder<CellFlagReasonBinding, FlagReasonHolder>(binding) {

    override fun onBindViewHolder(holder: FlagReasonHolder, position: Int) {
        binding.flagReasonTv.text = holder.content
        binding.root.setOnClick {
            it.findActionReceiverOrNull<FlagActionReceiver>()?.onClickFlagReason(holder)
        }
    }
}

interface FlagActionReceiver {
    fun onClickFlagReason(holder: FlagReasonHolder)
}