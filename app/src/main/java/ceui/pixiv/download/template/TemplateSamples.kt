package ceui.pixiv.download.template

import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.download.sanitize.FsSanitizer
import java.time.Instant

/**
 * Sample metadata per bucket for UI template previews. Calling [preview] lets
 * the settings screen show the user exactly what path their template will
 * produce, post-sanitization.
 */
object TemplateSamples {

    private val CREATED = Instant.parse("2024-08-15T09:32:10Z")

    val ILLUST_SAMPLE = ItemMeta(
        id = 123456789L,
        title = "夏日/祭り・花火",
        author = Author(55555L, "藍染 <Aizen>"),
        createdAt = CREATED,
        page = 1,
        totalPages = 4,
        width = 1920, height = 1080,
        flags = setOf(Flag.R18, Flag.Original),
    )

    val NOVEL_SAMPLE = ItemMeta(
        id = 7777777L,
        title = "Example Novel: Chapter 1",
        author = Author(12345L, "Example Author"),
        createdAt = CREATED,
        flags = setOf(Flag.R18, Flag.Series),
        seriesTitle = "Example Series",
        seriesOrder = 3,
        seriesTotal = 12,
    )

    /**
     * 合并下载的合集文件：id / title 都是**系列**的，`{chapters}` 是这份合集里的
     * 章节数。没有单篇序号，所以不给 seriesOrder——模板里写了 `{series_order}`
     * 的话预览就是空串，和真实渲染一致。
     */
    val NOVEL_SERIES_SAMPLE = ItemMeta(
        id = 1234567L,
        title = "Example Series",
        author = Author(12345L, "Example Author"),
        createdAt = CREATED,
        flags = setOf(Flag.Series),
        seriesTitle = "Example Series",
        seriesTotal = 12,
    )

    val UGOIRA_SAMPLE = ItemMeta(
        id = 88888L,
        title = "うごイラ sample",
        author = Author(999L, "Ugoira Artist"),
        createdAt = CREATED,
        flags = setOf(Flag.Animated),
    )

    /** 备份桶：`{title}` 是备份类型名，不是作品标题（见 DownloadItems.backupDestination）。 */
    val BACKUP_SAMPLE = ItemMeta(
        id = 0L,
        title = "Shaft-Backup",
        author = Author(0L, ""),
        createdAt = CREATED,
    )

    /** 日志桶：`{title}` 固定为 `log`，文件名主要靠 `{created}` 生成。 */
    val LOG_SAMPLE = ItemMeta(
        id = 0L,
        title = "log",
        author = Author(0L, ""),
        createdAt = CREATED,
    )

    private val DEFAULT_EXT: Map<Bucket, String> = mapOf(
        Bucket.Illust      to "jpg",
        Bucket.Ugoira      to "gif",
        Bucket.Novel       to "txt",
        Bucket.NovelSeries to "txt",
        Bucket.Caption     to "txt",
        Bucket.Backup      to "json",
        Bucket.Log         to "txt",
        Bucket.TempCache   to "bin",
    )

    private fun sampleFor(bucket: Bucket): ItemMeta = when (bucket) {
        Bucket.Novel -> NOVEL_SAMPLE
        Bucket.NovelSeries -> NOVEL_SERIES_SAMPLE
        Bucket.Ugoira -> UGOIRA_SAMPLE
        Bucket.Backup -> BACKUP_SAMPLE
        Bucket.Log -> LOG_SAMPLE
        else -> ILLUST_SAMPLE
    }

    fun preview(templateSource: String, bucket: Bucket): Preview {
        return try {
            val template = Template.compile(templateSource)
            val meta = sampleFor(bucket)
            val ext = DEFAULT_EXT.getValue(bucket)
            val raw = template.render(meta, ext)
            val cleaned = FsSanitizer.clean(raw)
            Preview.Ok(raw = raw, cleaned = cleaned)
        } catch (e: Exception) {
            Preview.Failure(e.message ?: "compile error")
        }
    }

    sealed interface Preview {
        data class Ok(val raw: RelativePath, val cleaned: RelativePath) : Preview
        data class Failure(val message: String) : Preview
    }
}
