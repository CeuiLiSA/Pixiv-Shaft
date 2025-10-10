package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import ceui.pixiv.ui.task.DownloadGifZipTask
import ceui.pixiv.ui.task.GifResourceTask
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.works.getGalleryHolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ArtworkViewModel(
    private val illustId: Long,
    private val taskPool: TaskPool
) : HoldersViewModel() {

    private val _illustLiveData = ObjectPool.get<Illust>(illustId)
    val illustLiveData: LiveData<Illust> = _illustLiveData

    private val _gifPack = MutableLiveData<GifPack>()
    private val _gifProgress = MutableLiveData<Int>()


    val galleryHolders = MutableLiveData<List<ListItemHolder>>()


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

        if (hint == RefreshHint.ErrorRetry) {
            delay(600L)
        }

        val context = Shaft.getContext()
        val illust = ObjectPool.get<Illust>(illustId).value ?: run {
            withContext(Dispatchers.IO) {
                val entity = AppDatabase.getAppDatabase(context).generalDao()
                    .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                entity?.typedObject<Illust>()?.also {
                    ObjectPool.update(it)
                    it.user?.let { user ->
                        ObjectPool.update(user)
                    }
                }
            }
        } ?: run {
            Client.appApi.getIllust(illustId).illust?.also {
                ObjectPool.update(it)
                it.user?.let { user ->
                    ObjectPool.update(user)
                }
            }
        } ?: return
        if (!illust.isAuthurExist()) {
            _refreshState.value = RefreshState.LOADED(
                hasContent = false, hasNext = false
            )
            throw RuntimeException("无法访问此内容")
        }


//        galleryHolders.value = getGalleryHolders(illust, MainScope(), taskPool) ?: listOf()

        val result = mutableListOf<ListItemHolder>()

        if (illust.isGif()) {
            viewModelScope.launch {
                val gifResponse =
                    GifResourceTask(illustId).awaitResult()
                val rc = DownloadGifZipTask(illustId, gifResponse, _gifProgress)
                val webpFile = rc.awaitResult()
                Timber.d("sadsadasw2 ${webpFile.path}")
                _gifPack.postValue(GifPack(gifResponse, webpFile))
            }
            result.add(GifHolder(illust, _gifProgress, _gifPack))
        } else {
            result.addAll(getGalleryHolders(illust, taskPool) ?: listOf())
        }

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