package ceui.pixiv.download.config

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.loxia.Novel
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.Template
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Factories that adapt legacy data models ([IllustsBean], [NovelBean]) into the
 * new [DownloadItem] domain type. Kept separate from [DownloadItem] itself so
 * the core model stays free of Pixiv-specific imports.
 */
object DownloadItems {

    @JvmStatic
    fun illustPage(illust: IllustsBean, pageIndex: Int): DownloadItem {
        val url = pageOriginalUrl(illust, pageIndex)
        val ext = extractExt(url, fallback = "png")
        return DownloadItem(
            bucket = Bucket.Illust,
            ext = ext,
            mime = mimeOf(ext),
            sourceUrl = url,
            meta = metaOf(illust, pageIndex),
        )
    }

    /** Final rendered GIF, saved to the user's gallery. */
    @JvmStatic
    fun ugoira(illust: IllustsBean): DownloadItem = DownloadItem(
        bucket = Bucket.Ugoira,
        ext = "gif",
        mime = "image/gif",
        sourceUrl = illust.imageUrls?.original.orEmpty(),
        meta = metaOf(illust, pageIndex = null),
    )

    /** Raw zip artefact downloaded from Pixiv before GIF rendering — app cache only. */
    @JvmStatic
    fun ugoiraZip(illust: IllustsBean): DownloadItem = DownloadItem(
        bucket = Bucket.TempCache,
        ext = "zip",
        mime = "application/zip",
        sourceUrl = illust.imageUrls?.original.orEmpty(),
        meta = metaOf(illust, pageIndex = null),
    )

    @JvmStatic
    fun novel(novel: NovelBean): DownloadItem = DownloadItem(
        bucket = Bucket.Novel,
        ext = "txt",
        mime = "text/plain",
        sourceUrl = "",
        meta = ItemMeta(
            id = novel.id.toLong(),
            title = novel.title.orEmpty(),
            author = Author(novel.user?.id?.toLong() ?: 0L, novel.user?.name.orEmpty()),
            createdAt = parseInstant(novel.create_date),
            page = null,
            totalPages = 1,
            width = null,
            height = null,
            flags = flagsOfNovel(novel),
        ),
    )

    /**
     * Render the template-based filename for a single illust page.
     * Use this everywhere instead of the legacy `buildPixivWorksFileName`.
     */
    @JvmStatic
    fun illustFileName(illust: IllustsBean, pageIndex: Int): String {
        val item = illustPage(illust, pageIndex)
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val template = Template.compile(resolved.template)
        val rendered = template.render(item.meta, item.ext, config.pageIndexFrom1)
        return FsSanitizer.clean(rendered).filename
    }

    /**
     * Render the template-based filename for a novel.
     * Use this everywhere instead of the legacy `buildPixivNovelFileName`.
     */
    @JvmStatic
    fun novelFileName(novelBean: NovelBean): String {
        val item = novel(novelBean)
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val template = Template.compile(resolved.template)
        val rendered = template.render(item.meta, item.ext, config.pageIndexFrom1)
        return FsSanitizer.clean(rendered).filename
    }

    /**
     * Novel from the loxia [Novel] model (used by V3 novel detail).
     */
    @JvmStatic
    fun novelFileNameFromLoxia(novel: Novel): String {
        val meta = ItemMeta(
            id = novel.id?.toLong() ?: 0L,
            title = novel.title.orEmpty(),
            author = Author(novel.user?.id?.toLong() ?: 0L, novel.user?.name.orEmpty()),
            createdAt = parseInstant(novel.create_date),
        )
        val item = DownloadItem(
            bucket = Bucket.Novel,
            ext = "txt",
            mime = "text/plain",
            sourceUrl = "",
            meta = meta,
        )
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val template = Template.compile(resolved.template)
        val rendered = template.render(item.meta, item.ext, config.pageIndexFrom1)
        return FsSanitizer.clean(rendered).filename
    }

    private fun metaOf(illust: IllustsBean, pageIndex: Int?): ItemMeta = ItemMeta(
        id = illust.id.toLong(),
        title = illust.title.orEmpty(),
        author = Author(illust.user?.id?.toLong() ?: 0L, illust.user?.name.orEmpty()),
        createdAt = parseInstant(illust.create_date),
        page = pageIndex,
        totalPages = illust.page_count.coerceAtLeast(1),
        width = illust.width.takeIf { it > 0 },
        height = illust.height.takeIf { it > 0 },
        flags = flagsOfIllust(illust),
    )

    private fun flagsOfIllust(illust: IllustsBean): Set<Flag> {
        val out = mutableSetOf<Flag>()
        if (illust.isR18File) out += Flag.R18
        if (illust.isCreatedByAI) out += Flag.AI
        if (illust.isGif) out += Flag.Animated
        return out
    }

    private fun flagsOfNovel(novel: NovelBean): Set<Flag> {
        val out = mutableSetOf<Flag>()
        if (novel.x_restrict > 0) out += Flag.R18
        return out
    }

    private fun pageOriginalUrl(illust: IllustsBean, index: Int): String =
        if (illust.page_count <= 1) {
            illust.meta_single_page?.original_image_url.orEmpty()
        } else {
            illust.meta_pages.getOrNull(index)?.image_urls?.original.orEmpty()
        }

    /**
     * Preserve the source's file type verbatim — Pixiv serves both `.png` and
     * `.jpg` originals, and the saved filename must reflect what the server
     * actually returns (never transcode the extension).
     */
    private fun extractExt(url: String, fallback: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val dot = clean.lastIndexOf('.')
        if (dot < 0 || dot == clean.length - 1) return fallback
        val raw = clean.substring(dot + 1)
        return raw.ifEmpty { fallback }.lowercase()
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "gif"         -> "image/gif"
        "webp"        -> "image/webp"
        "bmp"         -> "image/bmp"
        else          -> "image/$ext"
    }

    /**
     * Pixiv serves `create_date` as RFC-3339 with offset. We fall back to
     * "now" — not EPOCH — because a 1970 timestamp would silently produce a
     * bogus `{created:yyyy}` directory like `1970/01/`, scattering files.
     */
    private fun parseInstant(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.now()
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            Instant.now()
        }
    }

    private val IllustsBean.imageUrls get() = image_urls
}
