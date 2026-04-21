package ceui.pixiv.download.config

import com.tencent.mmkv.MMKV

/**
 * MMKV-backed persistence for [DownloadConfig].
 *
 * First launch:
 *   [load] sees no stored value → returns `LoadResult.FirstRun(fallback())` without
 *   saving. Callers decide when to persist (usually after the user finishes the
 *   initial setup screen) by calling [save].
 *
 * Corrupt payload:
 *   [load] returns `LoadResult.Corrupt(fallback(), cause)` and does NOT overwrite
 *   the bad payload. UI can surface this and offer repair / re-import so the
 *   user doesn't silently lose their config to a schema bug.
 *
 * Normal path:
 *   [load] returns `LoadResult.Ok(config)`.
 */
class DownloadConfigStore(
    private val fallback: () -> DownloadConfig,
    mmkvId: String = DEFAULT_MMKV_ID,
) {

    private val store: MMKV by lazy { MMKV.mmkvWithID(mmkvId) }

    sealed interface LoadResult {
        val config: DownloadConfig

        data class Ok(override val config: DownloadConfig) : LoadResult
        data class FirstRun(override val config: DownloadConfig) : LoadResult
        data class Corrupt(override val config: DownloadConfig, val cause: Throwable) : LoadResult
    }

    fun load(): LoadResult {
        val raw = store.decodeString(KEY) ?: return LoadResult.FirstRun(fallback())
        return try {
            LoadResult.Ok(DownloadConfigJson.fromJson(raw))
        } catch (t: Throwable) {
            LoadResult.Corrupt(fallback(), t)
        }
    }

    /** Convenience for callers that do not care about first-run / corrupt distinction. */
    fun loadOrFallback(): DownloadConfig = load().config

    fun save(config: DownloadConfig) {
        store.encode(KEY, DownloadConfigJson.toJson(config))
    }

    fun update(transform: (DownloadConfig) -> DownloadConfig): DownloadConfig {
        val next = transform(loadOrFallback())
        save(next)
        return next
    }

    fun reset() {
        store.removeValueForKey(KEY)
    }

    companion object {
        const val DEFAULT_MMKV_ID = "download_config_v1"
        private const val KEY = "config"
    }
}
