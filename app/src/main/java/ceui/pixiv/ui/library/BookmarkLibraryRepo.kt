package ceui.pixiv.ui.library

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.db.mirror.BookmarkAuthorFacet
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorDao
import ceui.pixiv.db.mirror.BookmarkMirrorEntity
import ceui.pixiv.db.mirror.BookmarkMirrorQuery
import ceui.pixiv.db.mirror.BookmarkTagFacet
import ceui.pixiv.db.mirror.BookmarkYearFacet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 收藏库页面的读侧门面：把 [BookmarkMirrorQuery] 拼出来的 SQL 跑掉，并把行还原成 [Illust]。
 *
 * 全部方法 main-safe（内部切 IO）。**不持有任何 Fragment / View**，可以安全地被
 * ViewModel 和 FeedSource 长期持有。
 */
object BookmarkLibraryRepo {

    private const val TAG = "BookmarkLibrary"

    /** 标签云一次最多给这么多；再多用户也扫不过来，还会把 sheet 撑成长列表。 */
    const val TAG_FACET_LIMIT = 120
    const val AUTHOR_FACET_LIMIT = 80

    private val dao: BookmarkMirrorDao
        get() = AppDatabase.getAppDatabase(Shaft.getContext()).bookmarkMirrorDao()

    suspend fun page(filter: BookmarkFilter, limit: Int, offset: Int): List<BookmarkMirrorEntity> =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val rows = dao.rawRows(BookmarkMirrorQuery.rows(filter, limit, offset))
            Timber.tag(TAG).d(
                "查询 offset=%d limit=%d sort=%s → %d 行，耗时 %dms",
                offset, limit, filter.sort, rows.size, (System.nanoTime() - startedAt) / 1_000_000,
            )
            rows
        }

    suspend fun count(filter: BookmarkFilter): Int = withContext(Dispatchers.IO) {
        dao.rawCount(BookmarkMirrorQuery.count(filter))
    }

    suspend fun tagFacets(filter: BookmarkFilter): List<BookmarkTagFacet> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        dao.rawTagFacets(BookmarkMirrorQuery.tagFacets(filter, TAG_FACET_LIMIT)).also {
            Timber.tag(TAG).d("标签云 %d 项，耗时 %dms", it.size, (System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    suspend fun authorFacets(filter: BookmarkFilter): List<BookmarkAuthorFacet> = withContext(Dispatchers.IO) {
        dao.rawAuthorFacets(BookmarkMirrorQuery.authorFacets(filter, AUTHOR_FACET_LIMIT))
    }

    suspend fun yearFacets(shelfKey: String): List<BookmarkYearFacet> = withContext(Dispatchers.IO) {
        dao.yearFacets(shelfKey)
    }

    suspend fun totalRows(shelfKey: String): Int = withContext(Dispatchers.IO) { dao.countOf(shelfKey) }

    /**
     * 行 → [Illust]。
     *
     * 坏行（旧版本写的 JSON、被截断的 payload）只丢这一条并留一条日志，不让整页炸掉：
     * 镜像表是后台攒了几万行的东西，一条坏行毁掉整个页面完全不成比例。
     */
    fun toIllust(row: BookmarkMirrorEntity): Illust? = deserialize(row, Illust::class.java)

    /** 行 → [Novel]。容错策略同 [toIllust]。 */
    fun toNovel(row: BookmarkMirrorEntity): Novel? = deserialize(row, Novel::class.java)

    private fun <T> deserialize(row: BookmarkMirrorEntity, clazz: Class<T>): T? = runCatching {
        Shaft.sGson.fromJson(row.payloadJson, clazz)
    }.onFailure {
        Timber.tag(TAG).w(it, "镜像行反序列化失败 shelf=%s id=%d", row.shelfKey, row.targetId)
    }.getOrNull()
}
