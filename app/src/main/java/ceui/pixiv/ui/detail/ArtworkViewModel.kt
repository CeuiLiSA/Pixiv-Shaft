package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.works.getGalleryHolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtworkViewModel(
    private val illustId: Long,
) : HoldersViewModel() {

    private val _illustLiveData = ObjectPool.get<Illust>(illustId)
    val illustLiveData: LiveData<Illust> = _illustLiveData

    private val _relatedIllustsDataSource = object : DataSource<Illust, IllustResponse>(
        dataFetcher = { Client.appApi.getRelatedIllusts(illustId) },
        itemMapper = { illust -> listOf(UserPostHolder(illust)) }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            if (holders.isNotEmpty()) {
                // 从现有列表中剔除 LoadingHolder
                val filteredList =
                    (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }
                        .toMutableList()

                // 添加新数据
                filteredList.addAll(holders)

                // 更新列表
                _itemHolders.value = filteredList
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true,
                    hasNext = hasNext()
                )
            } else {
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true,
                    hasNext = false
                )
            }
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val illust = ObjectPool.get<Illust>(illustId).value ?: run {
            withContext(Dispatchers.IO) {
                val entity = AppDatabase.getAppDatabase(context).generalDao()
                    .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                entity?.typedObject<Illust>()?.also {
                    ObjectPool.update(it)
                }
            }
        } ?: run {
            Client.appApi.getIllust(illustId).illust?.also {
                ObjectPool.update(it)
            }
        } ?: return
        val result = mutableListOf<ListItemHolder>()
        val images = getGalleryHolders(illust, MainScope())
        result.addAll(images ?: listOf())
        result.add(RedSectionHeaderHolder("标题"))
        result.add(ArtworkInfoHolder(illustId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(illust.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder("简介"))
        result.add(ArtworkCaptionHolder(illustId))
        result.add(
            RedSectionHeaderHolder(
                context.getString(R.string.related_artworks),
                type = SeeMoreType.RELATED_ILLUST,
                seeMoreString = context.getString(R.string.see_more)
            )
        )
        result.add(LoadingHolder(_relatedIllustsDataSource.refreshStateImpl) {
            viewModelScope.launch {
                _relatedIllustsDataSource.refreshImpl(
                    RefreshHint.ErrorRetry
                )
            }
        })
        _itemHolders.value = result
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = false
        )
        _relatedIllustsDataSource.refreshImpl(hint)
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        _relatedIllustsDataSource.loadMoreImpl()
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        val idList = mutableListOf<Long>()
        val filteredList =
            (_itemHolders.value ?: listOf()).filter { it is UserPostHolder }
        filteredList.mapNotNull { (it as? UserPostHolder)?.illust }.forEach { item ->
            idList.add(item.objectUniqueId)
        }
        ArtworksMap.store[fragmentUniqueId] = idList
    }
}