package ceui.pixiv.download

import android.content.Context
import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.pixiv.download.backend.AppCacheBackend
import ceui.pixiv.download.backend.MediaStoreBackend
import ceui.pixiv.download.backend.SafBackend
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketConfig
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
        val imagesStorage: StorageChoice = choice
        val downloadsStorage: StorageChoice = choice.forDownloadsBucket()
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

    /**
     * Update storage choices per bucket independently.
     * Bucket.Illust updates defaults.storage as baseline.
     * Non-image buckets normalize [StorageChoice.MediaStore.Collection.Images] to Downloads.
     */
    @JvmStatic
    fun updateBucketStorages(bucketStorages: Map<Bucket, StorageChoice>) {
        if (bucketStorages.isEmpty()) return
        require(Bucket.TempCache !in bucketStorages) {
            "Bucket.TempCache is not user-configurable"
        }
        store.update { cfg -> applyBucketStorages(cfg, bucketStorages) }
        invalidateBackends()
    }

    /**
     * Rebind SAF tree URI after re-authorization.
     * If any bucket (or defaults) already uses SAF, only those SAF entries are updated so
     * other buckets' Pictures/Downloads selections stay intact. Otherwise switches globally
     * to SAF for legacy / debug callers.
     */
    @JvmStatic
    fun rebindSafStorage(treeUri: Uri) {
        store.update { cfg -> applyRebindSafStorage(cfg, treeUri) }
        invalidateBackends()
    }

    internal fun applyBucketStorages(
        cfg: DownloadConfig,
        bucketStorages: Map<Bucket, StorageChoice>,
    ): DownloadConfig {
        if (bucketStorages.isEmpty()) return cfg
        require(Bucket.TempCache !in bucketStorages) {
            "Bucket.TempCache is not user-configurable"
        }
        var newDefaults = cfg.defaults
        val newPerBucket = cfg.perBucket.toMutableMap()
        bucketStorages[Bucket.Illust]?.let { illustStorage ->
            newDefaults = newDefaults.copy(storage = illustStorage)
        }
        bucketStorages.forEach { (bucket, storage) ->
            val normalized = when (bucket) {
                Bucket.Illust, Bucket.Ugoira -> storage
                else -> storage.forDownloadsBucket()
            }
            val existing = newPerBucket[bucket] ?: BucketConfig()
            newPerBucket[bucket] = existing.copy(storage = normalized)
        }
        return cfg.copy(defaults = newDefaults, perBucket = newPerBucket)
    }

    internal fun applyRebindSafStorage(cfg: DownloadConfig, treeUri: Uri): DownloadConfig {
        val choice = StorageChoice.Saf(treeUri)
        val hasExistingSaf = cfg.defaults.storage is StorageChoice.Saf
            || cfg.perBucket.values.any { it.storage is StorageChoice.Saf }
        if (!hasExistingSaf) {
            val newDefaults = cfg.defaults.copy(storage = choice)
            val newPerBucket = cfg.perBucket.mapValues { (_, bc) -> bc.copy(storage = choice) }
            return cfg.copy(defaults = newDefaults, perBucket = newPerBucket)
        }
        val newDefaults = if (cfg.defaults.storage is StorageChoice.Saf) {
            cfg.defaults.copy(storage = choice)
        } else {
            cfg.defaults
        }
        val newPerBucket = cfg.perBucket.mapValues { (_, bc) ->
            if (bc.storage is StorageChoice.Saf) bc.copy(storage = choice) else bc
        }
        return cfg.copy(defaults = newDefaults, perBucket = newPerBucket)
    }

    /** Returns the current effective images storage (from defaults). */
    @JvmStatic
    fun currentImagesStorage(): StorageChoice =
        store.loadOrFallback().defaults.storage

    /** True if the current config uses SAF for images. */
    @JvmStatic
    fun isSaf(): Boolean = currentImagesStorage() is StorageChoice.Saf
}
