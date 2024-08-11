package ceui.pixiv.ui.common

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.pixiv.ui.viewholdermap.ViewHolderFactory
import ceui.refactor.setOnClick
import java.lang.RuntimeException

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


class CommonAdapter(private val viewLifecycleOwner: LifecycleOwner) :
    ListAdapter<ListItemHolder, ListItemViewHolder<ViewBinding, ListItemHolder>>(
        listItemHolderDiffUtil
    ) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ListItemViewHolder<ViewBinding, ListItemHolder> {
        val autoGeneratedBuilder = ViewHolderFactory.VIEW_HOLDER_MAP[viewType]
        if (autoGeneratedBuilder == null) {
            throw RuntimeException("Add ItemHolder annotation")
        } else {
            return autoGeneratedBuilder.invoke(parent) as ListItemViewHolder<ViewBinding, ListItemHolder>
        }
    }

    override fun onBindViewHolder(
        holder: ListItemViewHolder<ViewBinding, ListItemHolder>,
        position: Int
    ) {
        val item = getItem(position)
        holder.lifecycleOwner = viewLifecycleOwner
        if (holder.binding is ViewDataBinding) {
            holder.binding.lifecycleOwner = viewLifecycleOwner
        }
        holder.onBindViewHolder(item, position)
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position).getItemViewType() // use layout id to unique the item type
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).getItemId()
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

    open fun getItemId(): Long {
        return 0L
    }
}

open class ListItemViewHolder<Binding : ViewBinding, T : ListItemHolder>(val binding: Binding) :
    RecyclerView.ViewHolder(binding.root) {

    protected val context: Context = binding.root.context
    var lifecycleOwner: LifecycleOwner? = null

    open fun onBindViewHolder(holder: T, position: Int) {

    }
}