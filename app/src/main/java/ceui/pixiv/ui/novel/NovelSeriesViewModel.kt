package ceui.pixiv.ui.novel

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesResp
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadMoreOwner
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.detail.ArtworkCaptionHolder
import ceui.pixiv.ui.detail.ArtworkInfoHolder
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.detail.UserInfoHolder
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.works.getGalleryHolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelSeriesViewModel(
    private val seriesId: Long,
) : ViewModel(), RefreshOwner, LoadMoreOwner, HoldersContainer {

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    private val _refreshState = MutableLiveData<RefreshState>()
    private var _lastOrder: Int? = null

    private val _series = MutableLiveData<NovelSeriesResp>()
    val series: LiveData<NovelSeriesResp> = _series

    private val _seriesNovelsDataSource = object : DataSource<Novel, NovelSeriesResp>(
        dataFetcher = { Client.appApi.getNovelSeries(seriesId, _lastOrder) },
        responseStore = createResponseStore({ "novel-series-$seriesId" }),
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            // 从现有列表中剔除 LoadingHolder
            val filteredList =
                (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }.toMutableList()

            // 添加新数据
            filteredList.addAll(holders)

            // 更新列表
            _itemHolders.value = filteredList
            _refreshState.value = RefreshState.LOADED(
                hasContent = true,
                hasNext = hasNext()
            )
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val resp = Client.appApi.getNovelSeries(seriesId)
                _series.value = resp
                val result = mutableListOf<ListItemHolder>()
                result.addAll(resp.displayList.map { novel -> NovelCardHolder(novel) })
                _lastOrder = resp.novels?.size
                _itemHolders.value = result
                val hasNext = resp.next_url != null
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true, hasNext = hasNext
                )
                if (hasNext) {
                    _seriesNovelsDataSource.refreshImpl(hint)
                }
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            }
        }
    }

    override fun prepareIdMap(fragmentUniqueId: String) {

    }

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders

    override fun loadMore() {
        viewModelScope.launch {
            _seriesNovelsDataSource.loadMoreImpl()
        }
    }
}