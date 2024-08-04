package ceui.pixiv.ui.list

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder
import com.google.gson.Gson
import kotlinx.coroutines.launch


fun <Item, T: KListShow<Item>> Fragment.pixivListViewModel(
    dataSourceProducer: () -> DataSource<Item, T>
): Lazy<PixivListViewModel<Item, T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dataSource = dataSourceProducer()
                return PixivListViewModel(dataSource) as T
            }
        }
    }
}


class PixivListViewModel<Item, T: KListShow<Item>>(
    private val dataSource: DataSource<Item, T>
) : ViewModel() {

    val refreshState: LiveData<RefreshState> = dataSource.refreshState
    val holders: LiveData<List<ListItemHolder>> = dataSource.itemHolders

    init {
        refresh(RefreshHint.initialLoad())
    }

    fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            dataSource.refreshData(hint)
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            dataSource.loadMoreData()
        }
    }
}