package ceui.refactor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.lisa.databinding.CellNoneBinding
import ceui.lisa.databinding.FragmentItemAaaaBinding
import ceui.lisa.databinding.FragmentItemBbbbBinding
import ceui.loxia.FlagReasonHolder
import ceui.loxia.FlagReasonViewHolder

object ViewHolderMapping {

    @Suppress("UNCHECKED_CAST")
    fun buildViewHolder(
        parent: ViewGroup,
        itemType: Int
    ): ListItemViewHolder<ViewDataBinding, ListItemHolder> {
        if (itemType == AAAAHolder::class.java.hashCode()) {
            return AAAAViewHolder(
                FragmentItemAaaaBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else if (itemType == BBBBHolder::class.java.hashCode()) {
            return BBBBViewHolder(
                FragmentItemBbbbBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else if (itemType == FlagReasonHolder::class.java.hashCode()) {
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