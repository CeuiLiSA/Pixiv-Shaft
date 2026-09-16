package ceui.pixiv.download.config

import ceui.lisa.activities.Shaft
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.NovelSeriesDetail
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
 * Factories that adapt legacy data models ([Illust]) into the
 * new [DownloadItem] domain type. Kept separate from [DownloadItem] itself so
 * the core model stays free of Pixiv-specific imports.
 */
object DownloadItems {

    @JvmStatic
    fun illustPage(illust: Illust, pageIndex: Int): DownloadItem {
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

    /**
     * 作品海报是原插画的派生图片：沿用 Illust 桶、作者/标题/页码元数据和用户模板，
     * Canvas 成品固定输出为 JPEG。调用方通过 Downloads.openDerived 添加防冲突后缀。
     */
    @JvmStatic
    fun illustPoster(illust: Illust, pageIndex: Int): DownloadItem = DownloadItem(
        bucket = Bucket.Illust,
        ext = "jpg",
        mime = "image/jpeg",
        sourceUrl = "",
        meta = metaOf(illust, pageIndex),
    )

    /**
     * 动图成品(存进用户相册的那一份)。格式随「动图保存格式」设置走 —— 默认 mp4。
     *
     * 模板里写死的 `.gif` 后缀会被 [ceui.pixiv.download.Downloads] 按 [DownloadItem.ext]
     * 归一,所以用户不改模板也能拿到正确后缀的文件。
     */
    @JvmStatic
    @JvmOverloads
    fun ugoira(
        illust: Illust,
        asMp4: Boolean = Shaft.sSettings.isUgoiraSaveAsMp4(),
    ): DownloadItem = DownloadItem(
        bucket = Bucket.Ugoira,
        ext = if (asMp4) "mp4" else "gif",
        mime = if (asMp4) "video/mp4" else "image/gif",
        sourceUrl = illust.imageUrls?.original.orEmpty(),
        meta = metaOf(illust, pageIndex = null),
    )

    /** Raw zip artefact downloaded from Pixiv before GIF rendering — app cache only. */
    @JvmStatic
    fun ugoiraZip(illust: Illust): DownloadItem = DownloadItem(
        bucket = Bucket.TempCache,
        ext = "zip",
        mime = "application/zip",
        sourceUrl = illust.imageUrls?.original.orEmpty(),
        meta = metaOf(illust, pageIndex = null),
    )

    /**
     * Full sanitized [RelativePath] (directory + filename) for a single illust
     * page, rendered through the user's active naming template. Used by the
     * aria2 remote dispatcher (#692) so files land on the NAS with the same
     * directory structure / naming the user configured for local downloads.
     */
    @JvmStatic
    fun illustRelativePath(illust: Illust, pageIndex: Int): RelativePath =
        renderedPath(illustPage(illust, pageIndex))

    /**
     * Ugoira-bucket counterpart of [illustRelativePath] — the rendered ugoira
     * template path. Used by the rename sweep (issue #567) to recompute what a
     * recorded download should be called under the active template.
     *
     * [asMp4] 必须按**那一行记录的实际后缀**传,不能读当前设置 —— 用户切成 mp4 之后
     * 早先存下的 GIF 仍然是 GIF,重命名不该把它改成 `.mp4`。
     */
    @JvmStatic
    fun ugoiraRelativePath(illust: Illust, asMp4: Boolean): RelativePath =
        renderedPath(ugoira(illust, asMp4))

    /**
     * Full sanitized destination for an illustration/manga caption txt.
     * Rendered through the active [Bucket.Caption] template (separate folder
     * by default, not mixed with image files).
     */
    @JvmStatic
    fun illustCaptionDestination(illust: Illust): RelativePath =
        renderedPath(illustCaptionItem(illust))

    private fun illustCaptionItem(illust: Illust): DownloadItem = DownloadItem(
        bucket = Bucket.Caption,
        ext = "txt",
        mime = "text/plain",
        sourceUrl = "",
        meta = metaOf(illust, pageIndex = null),
    )

    private fun renderedPath(item: DownloadItem): RelativePath {
        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(item.bucket)
        val rendered = SafeTemplateRender.render(
            resolved.template, item.bucket, item.meta, item.ext, config.pageNumbering,
        )
        return FsSanitizer.clean(rendered)
    }

    /**
     * Full sanitized [RelativePath] (directory + filename) for a novel from
     * the [Novel] model, rendered through the user's active naming
     * preset. 只取 filename 的调用方会把模板里的目录部分丢掉，`byAuthor` /
     * `byDate` / `detailed` 这些预设会静默失效——要写文件就用这个完整路径。
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
         * 本篇在系列**可见列表**中的 1-based 位置 + 系列总篇数。批量下载
         * （[ceui.pixiv.ui.task.BatchDownloadNovelsTask]）取列表下标，单篇
         * 导出查 [ceui.pixiv.cache.SeriesCache]——两边必须同源，才能渲染出同一个
         * 文件名。拿不到时传 null，`{series_order}` 渲染成空串。
         */
        seriesOrder: Int? = null,
        seriesTotal: Int? = null,
    ): RelativePath =
        novelDestination(
            novelItem(
                ItemMeta(
                    id = novel.id?.toLong() ?: 0L,
                    title = novel.title.orEmpty(),
                    author = Author(novel.user?.id ?: 0L, novel.user?.name.orEmpty()),
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
     * the [Novel] is usually present). Best-effort meta: author/created
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
     * 合并下载（单系列全章节合成一份）的完整落盘路径：目录 **和** 文件名都来自
     * 用户的 [Bucket.NovelSeries] 模板（issue #964）。此前这里只取小说模板的目录、
     * 文件名写死成 `<系列名>_合集_ID<id>`，用户既改不了名字，也没法把合集挪出
     * 小说模板里的 `{series}/` 子目录。
     *
     * [chapterCount] 是这份合集里**实际写进去**的章节数（用户中途停止就是截短后
     * 的数量），渲染成 `{chapters}`；[ext] 是导出格式的扩展名，渲染成 `{ext}`。
     * [r18] 由调用方看抓到的章节判定——系列接口本身不返回 x_restrict。
     *
     * Caveat — meta 的 `createdAt` 只能用 [Instant.now]：[NovelSeriesDetail] 不带
     * 发布 / 更新时间。按日期分组的预设因此把合集归到**下载当天**的年月，而不是
     * 系列真实的发布月。
     */
    @JvmStatic
    @JvmOverloads
    fun novelSeriesMergeDestination(
        seriesDetail: NovelSeriesDetail,
        chapterCount: Int,
        ext: String,
        r18: Boolean = false,
    ): RelativePath = novelDestination(
        novelSeriesItem(
            ItemMeta(
                id = seriesDetail.id,
                title = seriesDetail.title.orEmpty(),
                author = Author(
                    seriesDetail.user?.id ?: 0L,
                    seriesDetail.user?.name.orEmpty(),
                ),
                createdAt = Instant.now(),
                flags = seriesFlagOf(seriesDetail.title) + r18Flag(r18),
                seriesTitle = seriesTitleOf(seriesDetail.title),
                seriesTotal = chapterCount.takeIf { it > 0 },
            ),
            ext,
        ),
        extOverride = null,
    ).withExportExtension(ext)

    /**
     * 跨系列「全部合成一份」的落盘路径：这份文件不属于任何一个系列，`{series}` /
     * `{id}` 无从谈起，所以只从 [Bucket.NovelSeries] 模板取**目录**（用户挑的合集
     * 目录对它同样生效），文件名用调用方给的 [mergeFileName]。
     *
     * 同样有 `createdAt = Instant.now()` 的 caveat。`title` 留空：渲染出的文件名
     * 反正会被 [novelDestinationWithName] 丢掉，而自定义模板若在**目录**部分用了
     * `{title}`，留着系列名反倒会造出一个莫名其妙的文件夹。
     */
    @JvmStatic
    fun novelSeriesMergeDestinationForAuthor(
        authorId: Long,
        authorName: String?,
        chapterCount: Int,
        ext: String,
        mergeFileName: String,
    ): RelativePath = novelDestinationWithName(
        novelSeriesItem(
            ItemMeta(
                id = 0L,
                title = "",
                author = Author(authorId, authorName.orEmpty()),
                createdAt = Instant.now(),
                seriesTotal = chapterCount.takeIf { it > 0 },
            ),
            ext,
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
     * [novelItem] 的合并下载版：桶是 [Bucket.NovelSeries]，扩展名跟着导出格式走
     * （TXT / MD / EPUB / PDF 都用同一条模板，靠 `{ext}` 区分）。
     */
    private fun novelSeriesItem(meta: ItemMeta, ext: String): DownloadItem = DownloadItem(
        bucket = Bucket.NovelSeries,
        ext = ext,
        mime = novelMimeOf(ext),
        sourceUrl = "",
        meta = meta,
    )

    private fun novelMimeOf(ext: String): String = when (ext.lowercase()) {
        "md"   -> "text/markdown"
        "epub" -> "application/epub+zip"
        "pdf"  -> "application/pdf"
        else   -> "text/plain"
    }

    private fun r18Flag(r18: Boolean): Set<Flag> =
        if (r18) setOf(Flag.R18) else emptySet()

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

    /**
     * 备份导出落盘路径：完整使用 [Bucket.Backup] 模板渲染出的目录 + 文件名，
     * 不再用调用方的临时文件名覆盖。备份文件永远是 JSON，因此渲染时固定 ext=json。
     *
     * [fileName] 是调用方的备份名（`Shaft-Backup.json` / `Shaft-BrowseHistory.json` /
     * `Shaft-MuteRecords.json` / `Shaft-SynonymDict.json`），去掉后缀后作为 `{title}`
     * 喂给模板 —— 四种导出走同一个桶，文件名里必须还能看出来是哪一种。
     */
    @JvmStatic
    @JvmOverloads
    fun backupDestination(
        fileName: String,
        config: DownloadConfig = DownloadsRegistry.store.loadOrFallback(),
    ): RelativePath {
        val resolved = config.resolve(Bucket.Backup)
        return FsSanitizer.clean(
            SafeTemplateRender.render(
                resolved.template, Bucket.Backup, backupMeta(fileName), "json", config.pageNumbering,
            ),
        )
    }

    private fun backupMeta(fileName: String): ItemMeta = ItemMeta(
        id = 0L,
        title = fileName.substringBeforeLast('.'),
        author = Author(0L, ""),
        createdAt = Instant.now(),
    )

    /**
     * 合并下载的兜底：模板里把后缀写死成 `.txt`（或干脆不写后缀）时，导出 EPUB /
     * PDF 会得到一个打不开的假文件。只在结尾是**已知导出后缀**时替换、没有后缀时
     * 追加——不能无脑按最后一个点切，系列名里带点（`Vol.1 合集`）会被腰斩。
     */
    private fun RelativePath.withExportExtension(ext: String): RelativePath {
        val name = filename
        if (name.endsWith(".$ext", ignoreCase = true)) return this
        val current = name.substringAfterLast('.', "")
        val fixed = if (current.lowercase() in EXPORT_EXTENSIONS) {
            swapExtension(name, ext)
        } else {
            "$name.$ext"
        }
        return RelativePath(directory + fixed)
    }

    private val EXPORT_EXTENSIONS = setOf("txt", "md", "epub", "pdf")

    private fun swapExtension(filename: String, newExt: String): String {
        val dot = filename.lastIndexOf('.')
        val stem = if (dot in 1 until filename.length) filename.substring(0, dot) else filename
        return "$stem.$newExt"
    }

    private fun metaOf(illust: Illust, pageIndex: Int?): ItemMeta = ItemMeta(
        id = illust.id,
        title = illust.title.orEmpty(),
        author = Author(illust.user?.id ?: 0L, illust.user?.name.orEmpty()),
        createdAt = parseInstant(illust.create_date),
        page = pageIndex,
        totalPages = illust.page_count.coerceAtLeast(1),
        width = illust.width.takeIf { it > 0 },
        height = illust.height.takeIf { it > 0 },
        flags = flagsOfIllust(illust),
    )

    private fun flagsOfIllust(illust: Illust): Set<Flag> {
        val out = mutableSetOf<Flag>()
        if (illust.isR18File()) out += Flag.R18
        if (illust.isCreatedByAI()) out += Flag.AI
        if (illust.isGif()) out += Flag.Animated
        return out
    }

    /**
     * 小说的 flag 集。R18 必须在这里补上：内置 rFilter 预置的小说模板是
     * `[?R18:R18][?!R18:SFW]`，此前这条路径的 meta 不带任何 flag，R18 小说会被
     * `[?!R18:SFW]` 误归进 SFW/ 目录。
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

    private fun pageOriginalUrl(illust: Illust, index: Int): String =
        if (illust.page_count <= 1) {
            illust.meta_single_page?.original_image_url.orEmpty()
        } else {
            illust.meta_pages?.getOrNull(index)?.image_urls?.original.orEmpty()
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

    private val Illust.imageUrls get() = image_urls
}
