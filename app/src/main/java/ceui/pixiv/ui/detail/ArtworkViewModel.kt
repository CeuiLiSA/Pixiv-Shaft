package ceui.pixiv.ui.detail

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.models.ModelObject
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.common.ValueContent
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.user.UserPostHolder
import ceui.pixiv.ui.works.getGalleryHolders
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class ArtworkViewModel(
    private val illustId: Long
) : ViewModel(), RefreshOwner, HoldersContainer {

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    private val _refreshState = MutableLiveData<RefreshState>()

    private val valueContent = object : ValueContent<IllustResponse>(viewModelScope, {
        Client.appApi.getRelatedIllusts(illustId)
    }, responseStore = createResponseStore({ "related-illust-$illustId" })) {
        override fun applyResult(valueT: IllustResponse) {
            super.applyResult(valueT)
            // 从现有列表中剔除 LoadingHolder
            val filteredList =
                (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }.toMutableList()

            // 添加新数据
            filteredList.addAll(valueT.displayList.map { UserPostHolder(it) })

            // 更新列表
            _itemHolders.value = filteredList
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val context = Shaft.getContext()
                val illust = ObjectPool.get<Illust>(illustId).value ?: Client.appApi.getIllust(
                    illustId
                ).illust ?: return@launch
                ObjectPool.update(illust, true)
                val result = mutableListOf<ListItemHolder>()
                val images = getGalleryHolders(illust, viewModelScope)
                result.addAll(images ?: listOf())
                result.add(RedSectionHeaderHolder("标题"))
                result.add(ArtworkInfoHolder(illustId))
                result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
                result.add(UserInfoHolder(illust.user?.id ?: 0L))
                result.add(RedSectionHeaderHolder("简介"))
                result.add(ArtworkCaptionHolder(illustId))
                result.add(RedSectionHeaderHolder(context.getString(R.string.related_artworks)))
                result.add(LoadingHolder(valueContent.refreshState) {
                    valueContent.refresh(
                        RefreshHint.ErrorRetry
                    )
                })
                _itemHolders.value = result
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true, hasNext = false
                )
                valueContent.refresh(hint)
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            }
        }
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

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders
}