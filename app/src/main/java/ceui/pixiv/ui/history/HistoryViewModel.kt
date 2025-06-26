package ceui.pixiv.ui.history

import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.detail.UserInfoHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryViewModel(
    private val database: AppDatabase,
    private val recordType: Int
) : HoldersViewModel() {

    private var _offset = 0

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        _offset = 0
        val records = withContext(Dispatchers.IO) {
            val entityList = database.generalDao().getByRecordType(recordType, _offset)
            _offset += entityList.size
            entityList.mapNotNull { entity ->
                mapper(entity)
            }
        }
        _itemHolders.value = records
        _refreshState.value = RefreshState.LOADED(
            hasContent = records.isNotEmpty(),
            hasNext = records.size == 30
        )
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        val records = withContext(Dispatchers.IO) {
            val entityList = database.generalDao().getByRecordType(recordType, _offset)
            _offset += entityList.size
            entityList.mapNotNull { entity ->
                mapper(entity)
            }
        }
        _itemHolders.value = ((_itemHolders.value ?: listOf()) + records)
        _refreshState.value = RefreshState.LOADED(
            hasContent = true,
            hasNext = records.size == 30
        )
    }

    private fun mapper(entity: GeneralEntity): ListItemHolder? {
        return if (recordType == RecordType.VIEW_ILLUST_HISTORY || recordType == RecordType.BLOCK_ILLUST) {
            val illust = entity.typedObject<Illust>()
            IllustCardHolder(illust)
        } else if (recordType == RecordType.VIEW_USER_HISTORY || recordType == RecordType.BLOCK_USER || recordType == RecordType.FAVORITE_USER) {
            val user = entity.typedObject<User>()
            UserInfoHolder(user.id)
        } else if (recordType == RecordType.VIEW_NOVEL_HISTORY || recordType == RecordType.BLOCK_NOVEL) {
            val novel = entity.typedObject<Novel>()
            NovelCardHolder(novel)
        } else {
            null
        }
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        if (recordType == RecordType.VIEW_ILLUST_HISTORY || recordType == RecordType.BLOCK_ILLUST) {
            val filteredList = _itemHolders.value.orEmpty()
                .filterIsInstance<IllustCardHolder>()
                .map { it.illust.id }

            ArtworksMap.store[fragmentUniqueId] = filteredList
        } else if (recordType == RecordType.VIEW_NOVEL_HISTORY || recordType == RecordType.BLOCK_NOVEL) {
            val filteredList = _itemHolders.value.orEmpty()
                .filterIsInstance<NovelCardHolder>()
                .map { it.novel.id }

            ArtworksMap.store[fragmentUniqueId] = filteredList
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}