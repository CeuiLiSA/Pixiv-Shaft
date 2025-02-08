package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import kotlinx.coroutines.launch
import timber.log.Timber

open class HoldersViewModel : ViewModel(), HoldersContainer, RefreshOwner, LoadMoreOwner {

    protected val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    protected val _refreshState = MutableLiveData<RefreshState>()

    open suspend fun refreshImpl(hint: RefreshHint) {

    }

    final override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                refreshImpl(hint)
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            }
        }
    }

    override fun loadMore() {

    }

    override fun prepareIdMap(fragmentUniqueId: String) {
    }

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState

    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders
}