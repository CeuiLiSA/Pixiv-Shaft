package ceui.pixiv.download.template

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a (possibly user-authored) template [source] into a [RelativePath],
 * degrading gracefully when the persisted template is malformed.
 *
 * Why this exists: filename rendering happens deep inside synchronous,
 * uncatchable call sites. The legacy `DownloadItem` Java constructor
 * (`ceui.lisa.core.DownloadItem` → `FileCreator.customFileName`) renders on the
 * main thread the instant the user taps "download", and list adapters render it
 * during bind — both *before* the item ever reaches the download `Manager`. A
 * persisted template with an unsupported condition (e.g. `[?p<100:…]`, which
 * `TemplateContext.evaluate` rejects as an unknown flag) used to throw all the
 * way up and crash the app, since nothing on those paths catches it.
 *
 * The settings [TemplateValidator] now rejects such templates at save time;
 * this is the runtime safety net for configs that were *already* persisted
 * (older app versions, hand-edited config, imports). On any compile/render
 * failure we fall back to the bucket's [DefaultTemplates] source — the same
 * thing the user would get from "reset" — so the download still produces a sane
 * filename instead of taking down the process.
 *
 * Cache is keyed by source string; templates are immutable + thread-safe, so a
 * single process-lifetime cache is shared by every render site.
 */
object SafeTemplateRender {

    private val cache = ConcurrentHashMap<String, Template>()

    fun render(
        source: String,
        bucket: Bucket,
        meta: ItemMeta,
        ext: String,
        numbering: PageNumbering,
    ): RelativePath = try {
        compiled(source).render(meta, ext, numbering)
    } catch (e: Exception) {
        val fallback = DefaultTemplates.SOURCES[bucket] ?: DefaultTemplates.ILLUST
        compiled(fallback).render(meta, ext, numbering)
    }

    private fun compiled(source: String): Template =
        cache.getOrPut(source) { Template.compile(source) }
}
