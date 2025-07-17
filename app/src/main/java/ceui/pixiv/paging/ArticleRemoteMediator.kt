package ceui.pixiv.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RemoteKey
import timber.log.Timber

@OptIn(ExperimentalPagingApi::class)
class ArticleRemoteMediator(
    private val db: AppDatabase,
    private val repository: ArticleRepository,
    private val recordType: Int,     // 用于区分不同数据流
    private val entityType: Int      // Illust、Novel 等
) : RemoteMediator<Int, GeneralEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, GeneralEntity>
    ): MediatorResult {
        try {
            val generalDao = db.generalDao()
            val remoteKeyDao = db.remoteKeyDao()

            val nextPageUrl = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> {
                    val key = remoteKeyDao.getRemoteKey(recordType)
                    Timber.d("dsaadsdsaw2 ${key}")
                    key?.nextPageUrl
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                }
            }

            val response = repository.loadImpl(nextPageUrl)
            val illusts = response.displayList
            val newNextPageUrl = response.nextPageUrl

            // 写入数据库
            db.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    generalDao.deleteByRecordType(recordType)
                    remoteKeyDao.deleteByRecordType(recordType)
                }

                val entities = illusts.map { item ->
                    val json = Shaft.sGson.toJson(item)
                    GeneralEntity(
                        id = item.id, // 用你提供的 ID
                        json = json,
                        entityType = entityType,
                        recordType = recordType,
                        updatedTime = System.currentTimeMillis()
                    )
                }

                generalDao.insertAll(entities)
                remoteKeyDao.insert(RemoteKey(recordType, newNextPageUrl))
            }

            return MediatorResult.Success(endOfPaginationReached = newNextPageUrl == null)
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}
