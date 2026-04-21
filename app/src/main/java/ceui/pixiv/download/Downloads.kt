package ceui.pixiv.download

import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.ResolvedBucket
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.Template

/**
 * Facade — the only entrypoint the rest of the app uses to convert a
 * [DownloadItem] into a ready-to-write handle.
 *
 * Guarantees:
 *   1. Exactly one sanitization pass ([FsSanitizer]) runs between template
 *      rendering and storage, so any filesystem-illegal character that sneaks
 *      in through variable values is scrubbed here — no caller can bypass it.
 *   2. Templates are compiled lazily per template source and cached.
 *   3. Backends are resolved via the [backendFactory] using the
 *      [StorageChoice] in [DownloadConfig]; the factory should cache its own
 *      instances if appropriate.
 *   4. [OverwritePolicy] is enforced at the facade. Backends MUST NOT make
 *      overwrite decisions on their own.
 *   5. [Bucket.TempCache] is not user-configurable — it always uses
 *      [StorageChoice.AppCache] with a fixed template and Replace policy. This
 *      keeps intermediate artefacts (ugoira zip, unpacked frames) out of the
 *      user's gallery regardless of settings.
 *
 * Threading: [plan] and [open] perform I/O (exists / delete checks) and MUST
 * NOT be called from the main thread.
 */
class Downloads(
    private val configProvider: () -> DownloadConfig,
    private val backendFactory: (StorageChoice) -> StorageBackend,
) {

    constructor(
        config: DownloadConfig,
        backendFactory: (StorageChoice) -> StorageBackend,
    ) : this({ config }, backendFactory)

    private val templateCache = HashMap<String, Template>()

    fun plan(item: DownloadItem): Plan {
        val resolved: ResolvedBucket = resolveBucket(item.bucket)

        val template = templateCache.getOrPut(resolved.template) { Template.compile(resolved.template) }
        val raw: RelativePath = template.render(item.meta, item.ext)
        val cleaned: RelativePath = FsSanitizer.clean(raw)
        val backend: StorageBackend = backendFactory(resolved.storage)
        val (finalPath, skip) = applyOverwritePolicy(cleaned, backend, resolved.overwrite)
        return Plan(item, finalPath, backend, resolved.overwrite, skip)
    }

    /**
     * Opens a write handle for [item]. Returns `null` when the resolved plan
     * says to skip (Skip policy + file already exists).
     */
    fun open(item: DownloadItem): StorageBackend.WriteHandle? {
        val plan = plan(item)
        if (plan.skip) return null
        return plan.open()
    }

    /**
     * Bucket-scoped raw write — for callers that already have a final relative
     * path and just want the facade's backend dispatch + sanitization. Still
     * runs [FsSanitizer] and [OverwritePolicy], so the same filesystem
     * guarantees hold.
     *
     * Intended for legacy entry points (novel export, backup, log) that
     * bypass template rendering. New code should prefer [plan] / [open] via a
     * typed [DownloadItem].
     */
    fun openRaw(bucket: Bucket, rawPath: RelativePath, mime: String): StorageBackend.WriteHandle? {
        require(bucket != Bucket.TempCache) { "openRaw is not intended for TempCache" }
        val resolved = configProvider().resolve(bucket)
        val cleaned = FsSanitizer.clean(rawPath)
        val backend = backendFactory(resolved.storage)
        val (finalPath, skip) = applyOverwritePolicy(cleaned, backend, resolved.overwrite)
        if (skip) return null
        return backend.open(finalPath, mime)
    }

    private fun resolveBucket(bucket: Bucket): ResolvedBucket =
        if (bucket == Bucket.TempCache) TEMP_CACHE_FIXED else configProvider().resolve(bucket)

    private fun applyOverwritePolicy(
        path: RelativePath,
        backend: StorageBackend,
        policy: OverwritePolicy,
    ): Pair<RelativePath, Boolean> = when (policy) {
        OverwritePolicy.Skip    -> path to backend.exists(path)
        OverwritePolicy.Replace -> {
            if (backend.exists(path)) backend.delete(path)
            path to false
        }
        OverwritePolicy.Rename  -> nextFreePath(path, backend) to false
    }

    /**
     * Produces `name (1).ext`, `name (2).ext`, ... until [backend] reports the
     * segment does not exist. Extension-aware so the counter stays before the
     * dot.
     */
    private fun nextFreePath(path: RelativePath, backend: StorageBackend): RelativePath {
        if (!backend.exists(path)) return path
        val filename = path.filename
        val dot = filename.lastIndexOf('.')
        val hasExt = dot in 1 until filename.length
        val stem = if (hasExt) filename.substring(0, dot) else filename
        val ext = if (hasExt) filename.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = RelativePath(path.directory + "$stem ($i)$ext")
            if (!backend.exists(candidate)) return candidate
            i++
        }
    }

    data class Plan(
        val item: DownloadItem,
        val path: RelativePath,
        val backend: StorageBackend,
        val overwrite: OverwritePolicy,
        val skip: Boolean,
    ) {
        fun open(): StorageBackend.WriteHandle {
            check(!skip) { "Plan for ${path.joinTo()} is marked skip — do not open" }
            return backend.open(path, item.mime)
        }
    }

    companion object {
        private val TEMP_CACHE_FIXED = ResolvedBucket(
            template  = DefaultTemplates.TEMP,
            storage   = StorageChoice.AppCache,
            overwrite = OverwritePolicy.Replace,
        )
    }
}
