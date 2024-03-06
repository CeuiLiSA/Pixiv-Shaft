package ceui.refactor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.lisa.databinding.CellNoneBinding
import ceui.lisa.databinding.CellNovelImageBinding
import ceui.lisa.databinding.CellNovelTextBinding
import ceui.lisa.databinding.CellSpaceBinding
import ceui.lisa.databinding.CellTextDescBinding
import ceui.loxia.SpaceHolder
import ceui.loxia.SpaceViewHolder
import ceui.loxia.TextDescHolder
import ceui.loxia.TextDescViewHolder
import ceui.loxia.flag.FlagReasonHolder
import ceui.loxia.flag.FlagReasonViewHolder
import ceui.loxia.novel.NovelImageHolder
import ceui.loxia.novel.NovelImageViewHolder
import ceui.loxia.novel.NovelTextHolder
import ceui.loxia.novel.NovelTextViewHolder

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
        } else if (itemType == NovelTextHolder::class.java.hashCode()) {
            return NovelTextViewHolder(
                CellNovelTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else if (itemType == NovelImageHolder::class.java.hashCode()) {
            return NovelImageViewHolder(
                CellNovelImageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else if (itemType == SpaceHolder::class.java.hashCode()) {
            return SpaceViewHolder(
                CellSpaceBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            ) as ListItemViewHolder<ViewDataBinding, ListItemHolder>
        } else if (itemType == TextDescHolder::class.java.hashCode()) {
            return TextDescViewHolder(
                CellTextDescBinding.inflate(
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