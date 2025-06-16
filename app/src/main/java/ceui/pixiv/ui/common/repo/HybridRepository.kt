package ceui.pixiv.ui.common.repo

import ceui.pixiv.ui.common.ResponseStore


/**
 * Repository that uses a hybrid strategy combining local cache and remote data.
 *
 * Always fetches fresh data from a remote source and updates the local cache.
 * Also provides a fallback to cache via [ensureValue] if remote data is unavailable.
 *
 * @param ValueT the type of data this repository handles
 */
abstract class HybridRepository<ValueT> : Repository<ValueT>(), CacheReader<ValueT>,
    CacheWriter<ValueT> {

    /**
     * Final implementation of [load] that always fetches data from the remote source,
     * updates the cache, and returns a [LoadResult.REMOTE] instance.
     */
    final override suspend fun load(): LoadResult<ValueT> {
        val ret = fetchRemoteDataImpl().also {
            saveToCache(it)
        }
        return LoadResult.REMOTE(ret)
    }

    /**
     * Subclasses must implement this to fetch data from a remote source (e.g., API).
     */
    protected abstract suspend fun fetchRemoteDataImpl(): ValueT

    /**
     * Attempts to return data from cache; falls back to [load] if cache is missing.
     */
    suspend fun ensureValue(): ValueT = loadFromCache()?.data ?: load().data

    /**
     * Loads data from the cache and wraps it in [LoadResult.CACHE] if available.
     */
    final override suspend fun loadFromCache(): LoadResult<ValueT>? {
        val cache = loadFromCacheImpl()
        return cache?.let {
            LoadResult.CACHE(it.obj, it.updatedTime)
        }
    }

    /**
     * Subclasses must implement this to read cached data and its last update time.
     */
    protected abstract suspend fun loadFromCacheImpl(): DBCache<ValueT>?
}
