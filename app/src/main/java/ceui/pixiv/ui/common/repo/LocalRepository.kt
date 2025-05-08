package ceui.pixiv.ui.common.repo


/**
 * Repository that only reads data from local cache.
 * It never fetches from the network or writes back to storage.
 */
abstract class LocalRepository<ValueT> : Repository<ValueT>(), CacheReader<ValueT> {

    /**
     * Final implementation of [load] that delegates to cache loading only.
     */
    final override suspend fun load(): LoadResult<ValueT>? {
        return loadFromCache()
    }
}
