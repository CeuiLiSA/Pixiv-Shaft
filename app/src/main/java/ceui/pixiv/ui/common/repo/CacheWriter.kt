package ceui.pixiv.ui.common.repo


/**
 * Write-only cache interface.
 * Used to persist data to local cache.
 */
interface CacheWriter<ValueT> {
    suspend fun saveToCache(value: ValueT)
}
