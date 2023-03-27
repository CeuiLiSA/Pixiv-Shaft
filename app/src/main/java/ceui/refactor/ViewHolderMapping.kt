package ceui.refactor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.lisa.databinding.CellNoneBinding
import ceui.loxia.flag.FlagReasonHolder
import ceui.loxia.flag.FlagReasonViewHolder

object ViewHolderMapping {

    @Suppress("UNCHECKED_CAST")
    fun buildViewHolder(
        parent: ViewGroup,
        itemType: Int
    ): ListItemViewHolder<ViewDataBinding, ListItemHolder> {
        if (itemType == FlagReasonHolder::class.java.hashCode()) {
            return FlagReasonViewHolder(
                CellFlagReasonBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else {
            return ListItemViewHolder(
                CellNoneBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }
}