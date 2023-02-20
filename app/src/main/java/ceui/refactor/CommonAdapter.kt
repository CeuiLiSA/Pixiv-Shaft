package ceui.refactor

import android.view.ViewGroup
import android.widget.TextView
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellNoneBinding
import ceui.lisa.databinding.FragmentItemAaaaBinding
import ceui.lisa.databinding.FragmentItemBbbbBinding

val listItemHolderDiffUtil = object :
    DiffUtil.ItemCallback<ListItemHolder>() {
    override fun areItemsTheSame(
        oldItem: ListItemHolder,
        newItem: ListItemHolder
    ): Boolean {
        return oldItem.areItemsTheSame(newItem)
    }

    override fun areContentsTheSame(
        oldItem: ListItemHolder,
        newItem: ListItemHolder
    ): Boolean {
        return oldItem.areContentsTheSame(newItem)
    }
}


class CommonAdapter :
    ListAdapter<ListItemHolder, ListItemViewHolder<ViewDataBinding, ListItemHolder>>(
        listItemHolderDiffUtil
    ) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ListItemViewHolder<ViewDataBinding, ListItemHolder> {
        return ViewHolderMapping.buildViewHolder(parent, viewType)
    }

    override fun onBindViewHolder(
        holder: ListItemViewHolder<ViewDataBinding, ListItemHolder>,
        position: Int
    ) {
        holder.onBindViewHolder(getItem(position), position)
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position).getItemViewType() // use layout id to unique the item type
    }
}

open class ListItemHolder {

    open fun areItemsTheSame(other: ListItemHolder): Boolean {
        return this == other
    }

    open fun areContentsTheSame(other: ListItemHolder): Boolean {
        return this == other
    }

    fun getItemViewType(): Int {
        return this::class.java.hashCode()
    }
}

open class ListItemViewHolder<Binding : ViewDataBinding, T : ListItemHolder>(val binding: Binding) :
    RecyclerView.ViewHolder(binding.root) {

    open fun onBindViewHolder(holder: T, position: Int) {

    }
}

class AAAAHolder(val id: String, val content: String) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? AAAAHolder)?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? AAAAHolder)?.id &&
                content == (other as? AAAAHolder)?.content
    }
}

class AAAAViewHolder(binding: FragmentItemAaaaBinding) :
    ListItemViewHolder<FragmentItemAaaaBinding, AAAAHolder>(binding) {
    private val idView: TextView = binding.itemNumber
    private val contentView: TextView = binding.content

    override fun onBindViewHolder(holder: AAAAHolder, position: Int) {
        idView.text = (holder as? AAAAHolder)?.id
        contentView.text = (holder as? AAAAHolder)?.content
    }
}


class BBBBHolder(val id: String, val content: String) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? BBBBHolder)?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? BBBBHolder)?.id &&
                content == (other as? BBBBHolder)?.content
    }
}

class BBBBViewHolder(binding: FragmentItemBbbbBinding) :
    ListItemViewHolder<FragmentItemBbbbBinding, BBBBHolder>(binding) {
    private val idView: TextView = binding.itemNumber
    private val contentView: TextView = binding.content

    override fun onBindViewHolder(holder: BBBBHolder, position: Int) {
        idView.text = (holder as? BBBBHolder)?.id
        contentView.text = (holder as? BBBBHolder)?.content
    }
}

class NoneHolder(val id: String): ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? NoneHolder)?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? NoneHolder)?.id
    }
}

class NoneViewHolder(binding: CellNoneBinding) :
    ListItemViewHolder<CellNoneBinding, NoneHolder>(binding) {

    override fun onBindViewHolder(holder: NoneHolder, position: Int) {
    }
}