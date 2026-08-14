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
        compiled(source).render(meta, ext, numbering).forceUgoiraExt(bucket, ext)
    } catch (e: Exception) {
        val fallback = DefaultTemplates.SOURCES[bucket] ?: DefaultTemplates.ILLUST
        compiled(fallback).render(meta, ext, numbering).forceUgoiraExt(bucket, ext)
    }

    /**
     * 动图桶:渲染结果的后缀必须等于真实容器([ceui.pixiv.download.model.DownloadItem.ext])。
     *
     * 默认模板和全部预设都把 `.gif` **写死在模板串里**(不是 `{ext}` 占位符),用户把
     * 「动图保存格式」切成 mp4 后,不能拿到一个内容是 H.264、名字却叫 `.gif` 的文件 ——
     * 相册、分享、第三方 app 都按后缀判类型。这里是所有渲染路径的唯一必经点。
     *
     * 只对动图桶做:插画桶的后缀来自服务器真实返回的 URL、模板本来就写 `{ext}`,强制
     * 归一反而会改掉用户既有的文件名,还会让「是否已下载」的判定失准。
     */
    private fun RelativePath.forceUgoiraExt(bucket: Bucket, ext: String): RelativePath {
        if (bucket != Bucket.Ugoira || ext.isEmpty()) return this
        if (filename.endsWith(".$ext", ignoreCase = true)) return this
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        return RelativePath(directory + "$stem.$ext")
    }

    private fun compiled(source: String): Template =
        cache.getOrPut(source) { Template.compile(source) }
}
