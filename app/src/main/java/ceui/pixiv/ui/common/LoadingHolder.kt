package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.loxia.RefreshState
import ceui.loxia.setUpHolderRefreshState

class LoadingHolder(val refreshState: LiveData<RefreshState>, val retryBlock: () -> Unit) : ListItemHolder() {
}

@ItemHolder(LoadingHolder::class)
class LoadingViewHolder(bd: ItemLoadingBinding) : ListItemViewHolder<ItemLoadingBinding, LoadingHolder>(bd) {

    override fun onBindViewHolder(holder: LoadingHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.setUpHolderRefreshState(holder.refreshState, lifecycleOwner, holder.retryBlock)
    }
}