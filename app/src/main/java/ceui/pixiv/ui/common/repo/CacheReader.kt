package ceui.pixiv.ui.common.repo

/**
 * Read-only cache interface.
 * Used to retrieve data from local cache.
 */
interface CacheReader<ValueT> {
    suspend fun loadFromCache(): LoadResult<ValueT>?
}
