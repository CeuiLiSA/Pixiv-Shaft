package ceui.pixiv.ui.common.repo

import ceui.pixiv.ui.common.ResponseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class ResponseStoreRepository<ValueT>(
    val responseStore: ResponseStore<ValueT>,
) : HybridRepository<ValueT>() {

    override suspend fun loadFromCacheImpl(): DBCache<ValueT>? {
        return withContext(Dispatchers.IO) {
            responseStore.loadFromCache()
        }?.let {
            DBCache(obj = it, updatedTime = System.currentTimeMillis())
        }
    }

    override suspend fun saveToCache(value: ValueT) {
        responseStore.writeToCache(value)
    }
}