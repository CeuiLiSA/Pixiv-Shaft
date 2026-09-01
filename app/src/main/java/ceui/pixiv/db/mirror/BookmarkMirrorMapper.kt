package ceui.pixiv.db.mirror

import ceui.lisa.activities.Shaft
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.pixiv.api.model.Illust
import timber.log.Timber
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 网络模型（[Illust] / [Novel]）→ 镜像行 + 标签行。
 *
 * 这里是**唯一**决定「哪些字段被摊平成可筛选列」的地方。加一个筛选维度 = 在
 * [BookmarkMirrorEntity] 加一列 + 在这里填上 + 在 [BookmarkMirrorQuery] 加一个条件，
 * 三处对齐，别处不用动。
 *
 * 纯函数、无 Android 依赖（除了 [Shaft.sGson] 这一处序列化），可以单测。
 */
object BookmarkMirrorMapper {

    private const val TAG = "BookmarkMirror/Map"

    /** 画幅取向。方图的判定给 5% 容差，不然几乎没有作品算「方」。 */
    private const val SQUARE_TOLERANCE = 0.05f

    fun fromIllust(
        shelf: BookmarkShelf,
        illust: Illust,
        bookmarkSeq: Long,
        generation: Int,
        now: Long,
    ): MirrorRow {
        val tags = illust.tags.orEmpty()
        val tagRows = tagRows(shelf, illust.id, tags)
        val authorName = illust.user?.name.orEmpty()
        val title = illust.title.orEmpty()
        val width = illust.width
        val height = illust.height
        return MirrorRow(
            row = BookmarkMirrorEntity(
                shelfKey = shelf.key,
                targetId = illust.id,
                ownerUid = shelf.ownerUid,
                contentType = shelf.contentType.code,
                restrictCode = shelf.restrict.code,
                bookmarkSeq = bookmarkSeq,
                payloadJson = Shaft.sGson.toJson(illust),
                title = title,
                authorId = illust.user?.id ?: 0L,
                authorName = authorName,
                workType = illust.type ?: "illust",
                pageCount = illust.page_count,
                width = width,
                height = height,
                aspectRatio = aspectRatioOf(width, height),
                orientation = orientationOf(width, height),
                totalBookmarks = illust.total_bookmarks ?: 0,
                totalView = illust.total_view ?: 0,
                textLength = 0,
                createDateMs = parseCreateDate(illust.create_date),
                aiType = illust.illust_ai_type,
                xRestrict = illust.x_restrict ?: 0,
                sanityLevel = illust.sanity_level ?: 0,
                // pixiv 对已删除 / 仅限好P友的作品回 visible=false 且字段几乎全空。
                // 照样入库（用户自己收藏过的东西不该凭空消失），由界面上的
                // 「失效作品」筛选决定看不看。
                isVisible = illust.visible != false,
                isMuted = illust.is_muted == true,
                seriesId = illust.series?.id ?: 0L,
                // 用**真正入库的**行数，不是 tags.size：重名标签（pixiv 偶发下发）
                // 在 tagRows 里被去重了，用原始条数会和标签表对不上（真机上有 5 行不符）。
                tagCount = tagRows.size,
                searchText = buildSearchText(title, authorName, tags),
                syncedAt = now,
                generation = generation,
            ),
            tags = tagRows,
        )
    }

    fun fromNovel(
        shelf: BookmarkShelf,
        novel: Novel,
        bookmarkSeq: Long,
        generation: Int,
        now: Long,
    ): MirrorRow {
        val tags = novel.tags.orEmpty()
        val tagRows = tagRows(shelf, novel.id, tags)
        val authorName = novel.user?.name.orEmpty()
        val title = novel.title.orEmpty()
        return MirrorRow(
            row = BookmarkMirrorEntity(
                shelfKey = shelf.key,
                targetId = novel.id,
                ownerUid = shelf.ownerUid,
                contentType = shelf.contentType.code,
                restrictCode = shelf.restrict.code,
                bookmarkSeq = bookmarkSeq,
                payloadJson = Shaft.sGson.toJson(novel),
                title = title,
                authorId = novel.user?.id ?: 0L,
                authorName = authorName,
                workType = "novel",
                pageCount = novel.page_count ?: 1,
                width = 0,
                height = 0,
                aspectRatio = 0f,
                orientation = ORIENTATION_UNKNOWN,
                totalBookmarks = novel.total_bookmarks ?: 0,
                totalView = novel.total_view ?: 0,
                textLength = novel.text_length ?: 0,
                createDateMs = parseCreateDate(novel.create_date),
                aiType = novel.novel_ai_type,
                xRestrict = novel.x_restrict ?: 0,
                sanityLevel = 0,
                isVisible = novel.visible != false,
                isMuted = novel.is_muted == true,
                seriesId = novel.series?.id ?: 0L,
                // 用**真正入库的**行数，不是 tags.size：重名标签（pixiv 偶发下发）
                // 在 tagRows 里被去重了，用原始条数会和标签表对不上（真机上有 5 行不符）。
                tagCount = tagRows.size,
                searchText = buildSearchText(title, authorName, tags),
                syncedAt = now,
                generation = generation,
            ),
            tags = tagRows,
        )
    }

    private fun tagRows(shelf: BookmarkShelf, targetId: Long, tags: List<Tag>): List<BookmarkMirrorTagEntity> {
        if (tags.isEmpty()) return emptyList()
        // distinctBy：主键含 tagName，同一作品重名标签（pixiv 偶发）会在一次 insert 里
        // 撞主键。REPLACE 能兜住，但白白多一次删+插，不如在内存里先去掉。
        return tags.asSequence()
            .mapNotNull { tag ->
                val display = tag.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BookmarkMirrorTagEntity(
                    shelfKey = shelf.key,
                    targetId = targetId,
                    tagName = display.lowercase(Locale.ROOT),
                    displayName = display,
                    translatedName = tag.translated_name.orEmpty(),
                )
            }
            .distinctBy { it.tagName }
            .toList()
    }

    /**
     * 检索列：标题 + 作者 + 标签原名 + 标签译名，全部小写、空格分隔。
     *
     * 译名一起进去，是为了让中文用户搜「原创」也能命中 `オリジナル`。
     */
    private fun buildSearchText(title: String, authorName: String, tags: List<Tag>): String {
        val builder = StringBuilder(title.length + authorName.length + tags.size * 12)
        builder.append(title).append(' ').append(authorName)
        tags.forEach { tag ->
            tag.name?.let { builder.append(' ').append(it) }
            tag.translated_name?.let { builder.append(' ').append(it) }
        }
        return builder.toString().lowercase(Locale.ROOT)
    }

    private fun aspectRatioOf(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f

    private fun orientationOf(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return ORIENTATION_UNKNOWN
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > 1f + SQUARE_TOLERANCE -> ORIENTATION_LANDSCAPE
            ratio < 1f - SQUARE_TOLERANCE -> ORIENTATION_PORTRAIT
            else -> ORIENTATION_SQUARE
        }
    }

    /**
     * `2023-06-14T21:03:11+09:00` → epoch ms。解析不了给 0（= 未知，年份筛选自然落空）。
     *
     * 不复用 `DateParse`：那边是给界面拼展示串的，容错策略是「拿不到就编一个假日期」，
     * 落进可筛选列会变成一堆假的 2022-03-21。
     */
    fun parseCreateDate(raw: String?): Long {
        if (raw.isNullOrEmpty()) return 0L
        return try {
            ZonedDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (t: Throwable) {
            Timber.tag(TAG).v("create_date 解析失败，按未知处理: %s", raw)
            0L
        }
    }

    const val ORIENTATION_UNKNOWN = 0
    const val ORIENTATION_LANDSCAPE = 1
    const val ORIENTATION_PORTRAIT = 2
    const val ORIENTATION_SQUARE = 3
}

/** 一条作品映射出来的全部行：主表一行 + 标签表 N 行。 */
data class MirrorRow(
    val row: BookmarkMirrorEntity,
    val tags: List<BookmarkMirrorTagEntity>,
)
