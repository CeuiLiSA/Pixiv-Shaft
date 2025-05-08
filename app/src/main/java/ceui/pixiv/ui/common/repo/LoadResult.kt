package ceui.pixiv.ui.common.repo


/**
 * Represents the result of a data load operation, including the data,
 * the time it was last updated, and whether it came from cache or remote.
 */
sealed class LoadResult<T>(
    open val data: T,
    open val updatedTime: Long
) {
    /**
     * Result from a local cache source.
     *
     * @param updatedTime optional timestamp, defaults to current system time.
     */
    data class CACHE<T>(
        override val data: T,
        override val updatedTime: Long = System.currentTimeMillis()
    ) : LoadResult<T>(data, updatedTime)

    /**
     * Result from a remote (e.g., network) source.
     * Timestamp is automatically set to current time.
     */
    data class REMOTE<T>(
        override val data: T,
    ) : LoadResult<T>(data, System.currentTimeMillis())
}
