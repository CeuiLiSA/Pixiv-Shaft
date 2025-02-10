package ceui.pixiv.ui.history

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.IllustCardHolder
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
                if (recordType == RecordType.VIEW_ILLUST_HISTORY) {
                    val illust = entity.typedObject<Illust>()
                    IllustCardHolder(illust)
                } else if (recordType == RecordType.VIEW_USER_HISTORY) {
                    val user = entity.typedObject<User>()
                    UserInfoHolder(user.id)
                } else {
                    null
                }
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
                if (recordType == RecordType.VIEW_ILLUST_HISTORY) {
                    val illust = entity.typedObject<Illust>()
                    IllustCardHolder(illust)
                } else if (recordType == RecordType.VIEW_USER_HISTORY) {
                    val user = entity.typedObject<User>()
                    UserInfoHolder(user.id)
                } else {
                    null
                }
            }
        }
        _itemHolders.value = ((_itemHolders.value ?: listOf()) + records)
        _refreshState.value = RefreshState.LOADED(
            hasContent = true,
            hasNext = records.size == 30
        )
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}