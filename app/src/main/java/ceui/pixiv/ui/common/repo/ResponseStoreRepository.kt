package ceui.pixiv.ui.common.repo

import ceui.pixiv.ui.common.ResponseStore

abstract class ResponseStoreRepository<ValueT>(
    private val responseStore: ResponseStore<ValueT>,
) : HybridRepository<ValueT>() {

    override suspend fun loadFromCacheImpl(): DBCache<ValueT>? {
        return responseStore.loadFromCache()?.let {
            DBCache(obj = it, updatedTime = System.currentTimeMillis())
        }
    }

    override suspend fun saveToCache(value: ValueT) {
        responseStore.writeToCache(value)
    }
}