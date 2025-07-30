package ceui.pixiv.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.ModelObject
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RemoteKey

@OptIn(ExperimentalPagingApi::class)
class PagingRemoteMediator<ObjectT : ModelObject>(
    private val db: AppDatabase,
    private val repository: PagingMediatorRepository<ObjectT>,
    private val recordType: Int,
) : RemoteMediator<Int, GeneralEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, GeneralEntity>
    ): MediatorResult {
        try {
            val generalDao = db.generalDao()
            val remoteKeyDao = db.remoteKeyDao()

            val cacheTimeoutMs = 5 * 60 * 1000L // 5分钟
            val now = System.currentTimeMillis()

            val remoteKey = remoteKeyDao.getRemoteKey(recordType)
            val shouldSkipNetwork = if (loadType == LoadType.REFRESH) {
                remoteKey?.lastUpdatedTime?.let {
                    now - it < cacheTimeoutMs
                } ?: false
            } else {
                false
            }

            if (shouldSkipNetwork) {
                return MediatorResult.Success(endOfPaginationReached = false)
            }

            val nextPageUrl = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> {
                    remoteKey?.nextPageUrl
                        ?: return MediatorResult.Success(endOfPaginationReached = true)
                }
            }

            val response = repository.load(nextPageUrl)
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
                        id = item.objectUniqueId, // 用你提供的 ID
                        json = json,
                        entityType = item.objectType,
                        recordType = recordType,
                        updatedTime = System.currentTimeMillis()
                    )
                }

                generalDao.insertAll(entities)
                val newRemoteKey = RemoteKey(
                    recordType = recordType,
                    nextPageUrl = newNextPageUrl,
                    lastUpdatedTime = now
                )
                remoteKeyDao.insert(newRemoteKey)
            }

            return MediatorResult.Success(endOfPaginationReached = newNextPageUrl == null)
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}
