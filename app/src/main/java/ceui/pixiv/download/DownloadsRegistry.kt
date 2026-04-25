package ceui.pixiv.download

import android.content.Context
import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.pixiv.download.backend.AppCacheBackend
import ceui.pixiv.download.backend.MediaStoreBackend
import ceui.pixiv.download.backend.SafBackend
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadConfigStore
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket

/**
 * Process-wide entry point. Owns the [DownloadConfigStore] and the single
 * [Downloads] facade instance; provides a cached backend factory so repeated
 * plans with the same storage choice reuse the same backend object.
 *
 * Initialization is lazy and idempotent — callers can touch this from any
 * thread once the [Shaft] application has finished `onCreate` (MMKV needs to
 * be initialized first).
 */
object DownloadsRegistry {

    private val context: Context get() = Shaft.getContext()

    /**
     * First-run fallback. Derived from whatever legacy Settings the user might
     * already have — `downloadWay` (0=MediaStore / 1=SAF) and `rootPathUri`
     * (only meaningful when downloadWay=1) are the only fields still worth
     * respecting at bootstrap. Everything else collapses into the preset's
     * defaults.
     */
    private fun firstRunFallback(): DownloadConfig {
        val settings = Shaft.sSettings
        val images: StorageChoice = when {
            settings?.downloadWay == 1 && !settings.rootPathUri.isNullOrBlank() ->
                runCatching { StorageChoice.Saf(Uri.parse(settings.rootPathUri)) }
                    .getOrElse { StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images) }
            else ->
                StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        }
        val downloads: StorageChoice = when (images) {
            is StorageChoice.Saf -> images  // one tree for all user-visible artefacts
            else -> StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        }
        return ConfigPresets.shaftClassic(images, downloads)
    }

    @JvmStatic
    val store: DownloadConfigStore by lazy {
        DownloadConfigStore(::firstRunFallback)
    }

    /**
     * Backend instances are cached per [StorageChoice] — building a
     * [SafBackend] walks a DocumentFile tree, not free.
     */
    private val backendCache = HashMap<StorageChoice, StorageBackend>()

    private fun backendFor(choice: StorageChoice): StorageBackend =
        synchronized(backendCache) {
            backendCache.getOrPut(choice) {
                when (choice) {
                    is StorageChoice.MediaStore -> MediaStoreBackend(context, choice.collection)
                    is StorageChoice.Saf        -> SafBackend(context, choice.treeUri)
                    StorageChoice.AppCache      -> AppCacheBackend(context)
                }
            }
        }

    @JvmStatic
    val downloads: Downloads by lazy {
        Downloads({ store.loadOrFallback() }, ::backendFor)
    }

    /** Drop cached backends — call after the user changes download settings. */
    @JvmStatic
    fun invalidateBackends() {
        synchronized(backendCache) { backendCache.clear() }
    }

    /**
     * Switch all buckets to the given storage.
     * - Pictures: images→Pictures, downloads→Downloads
     * - Downloads: everything→Downloads
     * - SAF: everything→same tree
     */
    @JvmStatic
    fun applyGlobalStorage(choice: StorageChoice) {
        val imagesStorage: StorageChoice
        val downloadsStorage: StorageChoice
        when (choice) {
            is StorageChoice.Saf -> {
                imagesStorage = choice
                downloadsStorage = choice
            }
            is StorageChoice.MediaStore -> {
                imagesStorage = choice
                downloadsStorage = when (choice.collection) {
                    StorageChoice.MediaStore.Collection.Images ->
                        StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
                    StorageChoice.MediaStore.Collection.Downloads -> choice
                }
            }
            StorageChoice.AppCache -> {
                imagesStorage = choice
                downloadsStorage = choice
            }
        }
        store.update { cfg ->
            // Preserve per-bucket templates but replace all storage choices
            val newDefaults = cfg.defaults.copy(storage = imagesStorage)
            val newPerBucket = cfg.perBucket.mapValues { (bucket, bc) ->
                val newStorage = when (bucket) {
                    Bucket.Illust, Bucket.Ugoira -> imagesStorage
                    else -> downloadsStorage
                }
                bc.copy(storage = newStorage)
            }
            cfg.copy(defaults = newDefaults, perBucket = newPerBucket)
        }
        invalidateBackends()
    }

    /** Returns the current effective images storage (from defaults). */
    @JvmStatic
    fun currentImagesStorage(): StorageChoice =
        store.loadOrFallback().defaults.storage

    /** True if the current config uses SAF for images. */
    @JvmStatic
    fun isSaf(): Boolean = currentImagesStorage() is StorageChoice.Saf
}
