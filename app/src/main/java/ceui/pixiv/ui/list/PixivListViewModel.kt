package ceui.pixiv.ui.list

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.DataSourceContainer
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadMoreOwner
import ceui.pixiv.ui.common.RefreshOwner
import kotlinx.coroutines.launch

fun <Item, T : KListShow<Item>, ArgsT: Any> Fragment.pixivListViewModel(
    argsProducer: () -> ArgsT,
    dataSourceProducer: (ArgsT) -> DataSource<Item, T>
): Lazy<PixivListViewModel<Item, T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val args = argsProducer()
                val dataSource = dataSourceProducer(args)
                return PixivListViewModel(dataSource) as T
            }
        }
    }
}

fun <Item, T : KListShow<Item>> Fragment.pixivListViewModel(
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


class PixivListViewModel<Item, T : KListShow<Item>>(
    private val _dataSource: DataSource<Item, T>
) : ViewModel(), RefreshOwner, LoadMoreOwner, HoldersContainer, DataSourceContainer<Item, T> {

    override val refreshState: LiveData<RefreshState> = _dataSource.refreshStateImpl
    override val holders: LiveData<List<ListItemHolder>> = _dataSource.itemHoldersImpl

    override fun prepareIdMap(fragmentUniqueId: String) {
        _dataSource.prepareIdMapImpl(fragmentUniqueId)
    }

    init {
        if (_dataSource.initialLoad()) {
            refresh(RefreshHint.InitialLoad)
        }
    }

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            _dataSource.refreshImpl(hint)
        }
    }

    override fun loadMore() {
        viewModelScope.launch {
            _dataSource.loadMoreImpl()
        }
    }

    override fun dataSource(): DataSource<*, *> {
        return _dataSource
    }

    override fun <DataSourceT : DataSource<Item, T>> typedDataSource(): DataSourceT {
        return _dataSource as DataSourceT
    }
}