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
        flags = setOf(Flag.R18),
    )

    val UGOIRA_SAMPLE = ItemMeta(
        id = 88888L,
        title = "うごイラ sample",
        author = Author(999L, "Ugoira Artist"),
        createdAt = CREATED,
        flags = setOf(Flag.Animated),
    )

    private val DEFAULT_EXT: Map<Bucket, String> = mapOf(
        Bucket.Illust    to "jpg",
        Bucket.Ugoira    to "gif",
        Bucket.Novel     to "txt",
        Bucket.Backup    to "zip",
        Bucket.Log       to "txt",
        Bucket.TempCache to "bin",
    )

    private fun sampleFor(bucket: Bucket): ItemMeta = when (bucket) {
        Bucket.Novel -> NOVEL_SAMPLE
        Bucket.Ugoira -> UGOIRA_SAMPLE
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
