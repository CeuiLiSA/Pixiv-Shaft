package ceui.pixiv.download.config

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.models.NovelSeriesItem
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.sanitize.FsSanitizer
import ceui.pixiv.download.template.SafeTemplateRender
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
            seriesTitle = seriesTitleOf(novel.series?.title),
        ),
    )

    /**
     * Render the template-based filename for a single illust page.
     * Use this everywhere instead of the legacy `buildPixivWorksFileName`.
     */
    @JvmStatic
    fun illustFileName(illust: IllustsBean, pageIndex: Int): String =
        illustRelativePath(illust, pageIndex).filename

    /**
     * Full sanitized [RelativePath] (directory + filename) for a single illust
     * page, rendered through the user's active naming template. Used by the
     * aria2 remote dispatcher (#692) so files land on the NAS with the same
     * directory structure / naming the user configured for local downloads.
     */
    @JvmStatic
    fun illustRelativePath(illust: IllustsBean, pageIndex: Int): RelativePath =
        renderedPath(illustPage(illust, pageIndex))

    /**
     * Ugoira-bucket counterpart of [illustRelativePath] — the rendered GIF's
     * template path. Used by the rename sweep (issue #567) to recompute what a
     * recorded `.gif` download should be called under the active template.
     */
    @JvmStatic
    fun ugoiraRelativePath(illust: IllustsBean): RelativePath =
        renderedPath(ugoira(illust))

    private fun renderedPath(item: DownloadItem): RelativePath {
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val rendered = SafeTemplateRender.render(
            resolved.template, item.bucket, item.meta, item.ext, config.pageNumbering,
        )
        return FsSanitizer.clean(rendered)
    }

    /**
     * Render the template-based filename for a novel.
     * Use this everywhere instead of the legacy `buildPixivNovelFileName`.
     */
    @JvmStatic
    fun novelFileName(novelBean: NovelBean): String =
        novelDestination(novel(novelBean), extOverride = null).filename

    /**
     * Full sanitized [RelativePath] (directory + filename) for a novel
     * from the legacy [NovelBean] model. Mirrors [novelDestinationFromLoxia]
     * but for the Java-era download path that still flows through
     * [ceui.lisa.download.IllustDownload]. Without this, the Java side
     * would have to keep hardcoding `ShaftNovels/{Novel_id_title}.txt` and
     * sidestep the user's naming preset entirely.
     */
    @JvmStatic
    fun novelDestinationFromBean(novelBean: NovelBean): RelativePath =
        novelDestination(novel(novelBean), extOverride = null)

    /**
     * Novel from the loxia [Novel] model (used by V3 novel detail).
     * Returns just the basename — for MediaStore DISPLAY_NAME lookups.
     */
    @JvmStatic
    fun novelFileNameFromLoxia(novel: Novel): String =
        novelDestinationFromLoxia(novel).filename

    /**
     * Full sanitized [RelativePath] (directory + filename) for a novel from
     * the loxia [Novel] model, rendered through the user's active naming
     * preset. Use this — not [novelFileNameFromLoxia] — when you actually
     * want to write the file: callers that only pass the filename strip the
     * directory portion of the user's template, which silently breaks
     * `byAuthor` / `byDate` / `detailed` presets.
     *
     * All four ReaderV3 export formats funnel through this method: TXT
     * passes `extOverride = "txt"` (a no-op swap, since the bundled novel
     * templates already end in `.txt`); MD / EPUB / PDF pass their own
     * extension to swap the trailing `.txt` of the rendered template.
     * Default `null` means «keep whatever the template rendered» (used by
     * the queued downloader, which only writes TXT).
     */
    @JvmStatic
    @JvmOverloads
    fun novelDestinationFromLoxia(
        novel: Novel,
        extOverride: String? = null,
        /**
         * 本篇在系列中的 1-based 序号 + 系列总篇数，只有「从系列批量下载」
         * （[ceui.pixiv.ui.task.BatchDownloadNovelsTask]）能提供；单篇下载 /
         * 阅读器导出拿不到顺序，`{series_order}` 渲染成空串。
         */
        seriesOrder: Int? = null,
        seriesTotal: Int? = null,
    ): RelativePath =
        novelDestination(
            novelItem(
                ItemMeta(
                    id = novel.id?.toLong() ?: 0L,
                    title = novel.title.orEmpty(),
                    author = Author(novel.user?.id?.toLong() ?: 0L, novel.user?.name.orEmpty()),
                    createdAt = parseInstant(novel.create_date),
                    flags = flagsOfLoxiaNovel(novel),
                    seriesTitle = seriesTitleOf(novel.series?.title),
                    seriesOrder = seriesOrder,
                    seriesTotal = seriesTotal,
                ),
            ),
            extOverride = extOverride,
        )

    /**
     * For ReaderV3 export when only the WebNovel payload is known (rare —
     * the loxia [Novel] is usually present). Best-effort meta: author/created
     * are unavailable, so templates that lean on them get blank values, but
     * the path is still rendered through the active preset so the file lands
     * in the user's chosen folder.
     */
    @JvmStatic
    fun novelDestinationFromWeb(
        webNovelId: String?,
        webNovelTitle: String?,
        extOverride: String,
    ): RelativePath = novelDestination(
        novelItem(
            ItemMeta(
                id = webNovelId?.toLongOrNull() ?: 0L,
                title = webNovelTitle.orEmpty(),
                author = Author(0L, ""),
                createdAt = Instant.now(),
            ),
        ),
        extOverride = extOverride,
    )

    /**
     * Resolve the directory portion (everything except the filename) of the
     * user's Novel-bucket template, evaluated against [seriesDetail]. Series
     * merges (合集) don't fit per-novel templates — they have their own
     * filename — but the user still wants them next to their other novels,
     * so we render the template and keep only the directory.
     *
     * Returned path's filename is the caller-supplied [mergeFileName].
     *
     * Caveat — the meta passed in uses [Instant.now] for `createdAt`,
     * because [NovelSeriesDetail] does not expose a publication / last-
     * updated timestamp. Date-bucketing presets (`byDate`,
     * `byAuthorAndDate`) therefore file the merge under the **download
     * day's** year/month, not the series' true publication month.
     */
    @JvmStatic
    fun novelMergeDestination(
        seriesDetail: NovelSeriesDetail,
        mergeFileName: String,
    ): RelativePath = novelDestinationWithName(
        novelItem(
            ItemMeta(
                id = seriesDetail.id,
                title = seriesDetail.title.orEmpty(),
                author = Author(
                    seriesDetail.user?.id ?: 0L,
                    seriesDetail.user?.name.orEmpty(),
                ),
                createdAt = Instant.now(),
                flags = seriesFlagOf(seriesDetail.title),
                seriesTitle = seriesTitleOf(seriesDetail.title),
            ),
        ),
        mergeFileName,
    )

    /**
     * Java-side variant of [novelMergeDestination] taking the legacy
     * [NovelSeriesItem] model. Same semantics: directory from the user's
     * Novel-bucket template, filename from [mergeFileName]. Same
     * `createdAt = Instant.now()` caveat as [novelMergeDestination].
     */
    @JvmStatic
    fun novelMergeDestinationForSeriesItem(
        seriesItem: NovelSeriesItem,
        mergeFileName: String,
    ): RelativePath = novelDestinationWithName(
        novelItem(
            ItemMeta(
                id = seriesItem.id.toLong(),
                title = seriesItem.title.orEmpty(),
                author = Author(
                    seriesItem.user?.id?.toLong() ?: 0L,
                    seriesItem.user?.name.orEmpty(),
                ),
                createdAt = Instant.now(),
                flags = seriesFlagOf(seriesItem.title),
                seriesTitle = seriesTitleOf(seriesItem.title),
            ),
        ),
        mergeFileName,
    )

    /**
     * Variant of [novelMergeDestination] for paths where no series detail
     * is available (e.g. cross-series "all merged into one" output keyed
     * only by author). The directory is resolved from the active Novel-
     * bucket template and [mergeFileName] is used verbatim as the
     * filename.
     *
     * Same `createdAt = Instant.now()` caveat as [novelMergeDestination]
     * — date-bucketing presets file under the download day. `title` is
     * left blank: the rendered filename is discarded anyway by
     * [novelDestinationWithName], and any custom template that includes
     * `{title}` in its **directory** part would otherwise produce a
     * nonsensical folder named after the merge filename.
     */
    @JvmStatic
    fun novelMergeDestinationForAuthor(
        authorId: Int,
        authorName: String?,
        mergeFileName: String,
    ): RelativePath = novelDestinationWithName(
        novelItem(
            ItemMeta(
                id = 0L,
                title = "",
                author = Author(authorId.toLong(), authorName.orEmpty()),
                createdAt = Instant.now(),
            ),
        ),
        mergeFileName,
    )

    /**
     * Boilerplate factory: every novel write path needs the same
     * `(Bucket.Novel, ext="txt", mime="text/plain", sourceUrl="")`
     * envelope. Wrapped here so the per-call sites only have to express
     * the parts that actually vary (the [meta]).
     */
    private fun novelItem(meta: ItemMeta): DownloadItem = DownloadItem(
        bucket = Bucket.Novel,
        ext = "txt",
        mime = "text/plain",
        sourceUrl = "",
        meta = meta,
    )

    /**
     * Render → sanitize → optionally swap extension on the last segment.
     * Centralised so every novel write path goes through the same template
     * resolution; presets cannot accidentally apply to one path and not
     * another.
     */
    private fun novelDestination(item: DownloadItem, extOverride: String?): RelativePath {
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val rendered = SafeTemplateRender.render(
            resolved.template, item.bucket, item.meta, item.ext, config.pageNumbering,
        )
        val cleaned = FsSanitizer.clean(rendered)
        if (extOverride.isNullOrEmpty()) return cleaned
        val newName = swapExtension(cleaned.filename, extOverride)
        return RelativePath(cleaned.directory + newName)
    }

    /**
     * Render the template just to extract the directory, then attach
     * [overrideName] (already-sanitized callsite filename) as the basename.
     * Used by series-merge tasks that need to honour the user's folder
     * choice while keeping their own filename convention.
     */
    private fun novelDestinationWithName(
        item: DownloadItem,
        overrideName: String,
    ): RelativePath {
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val rendered = SafeTemplateRender.render(
            resolved.template, item.bucket, item.meta, item.ext, config.pageNumbering,
        )
        val cleaned = FsSanitizer.clean(rendered)
        val finalName = FsSanitizer.cleanSegment(overrideName, preserveExtension = true)
        return RelativePath(cleaned.directory + finalName)
    }

    private fun swapExtension(filename: String, newExt: String): String {
        val dot = filename.lastIndexOf('.')
        val stem = if (dot in 1 until filename.length) filename.substring(0, dot) else filename
        return "$stem.$newExt"
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
        if (seriesTitleOf(novel.series?.title) != null) out += Flag.Series
        return out
    }

    /**
     * loxia [Novel] 版本的 [flagsOfNovel]。R18 必须在这里补上：内置 rFilter 预置的
     * 小说模板是 `[?R18:R18][?!R18:SFW]`，此前这条路径的 meta 不带任何 flag，
     * R18 小说会被 `[?!R18:SFW]` 误归进 SFW/ 目录。
     */
    private fun flagsOfLoxiaNovel(novel: Novel): Set<Flag> {
        val out = mutableSetOf<Flag>()
        if ((novel.x_restrict ?: 0) > 0) out += Flag.R18
        if (seriesTitleOf(novel.series?.title) != null) out += Flag.Series
        return out
    }

    /**
     * Pixiv 对不在系列中的小说返回 `series: {}` 空对象，反序列化后对象非 null
     * 但字段全空 —— 判定「真的在系列里」必须看标题非空（与
     * [ceui.pixiv.download.header.NovelHeaderRenderer] 的判法一致），
     * 不能只判 `series != null`。
     */
    private fun seriesTitleOf(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    private fun seriesFlagOf(raw: String?): Set<Flag> =
        if (seriesTitleOf(raw) != null) setOf(Flag.Series) else emptySet()

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
