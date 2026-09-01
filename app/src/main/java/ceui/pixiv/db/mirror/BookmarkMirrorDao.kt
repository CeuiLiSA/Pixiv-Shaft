package ceui.pixiv.db.mirror

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** [BookmarkMirrorDao.existingSeqs] 的投影：库里已有的 (作品 id, 收藏序号)。 */
data class MirrorIdSeq(val targetId: Long, val bookmarkSeq: Long)

/**
 * 收藏镜像的读写口。
 *
 * 阻塞式（对齐本仓 DAO 约定 + `allowMainThreadQueries`），调用方负责切 IO ——
 * 唯二的例外是 [observeStates] / [observeCount] 这两个 [Flow]，Room 自己在
 * 查询执行器上跑。
 */
@Dao
interface BookmarkMirrorDao {

    // ─────────────────────────── 写入 ───────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRows(rows: List<BookmarkMirrorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTags(tags: List<BookmarkMirrorTagEntity>)

    @Query("DELETE FROM bookmark_mirror_tag_table WHERE shelfKey = :shelfKey AND targetId IN (:targetIds)")
    fun deleteTagsOf(shelfKey: String, targetIds: List<Long>)

    /**
     * 落一页：先清掉这批作品的旧标签行，再整体覆写主表与标签表。
     *
     * 必须是一个事务：主表写进去了而标签没写，界面上这些作品就凭空少了标签；
     * 反过来清了标签又没写回，标签云会缺一块。中途被杀也只会整页回滚，
     * 而续传游标是**在这之后**才落盘的（见引擎），所以最坏结果是下次重放这一页 ——
     * 重放是幂等的（REPLACE + 序号由调用方保持不变）。
     */
    @Transaction
    fun writePage(rows: List<BookmarkMirrorEntity>, tags: List<BookmarkMirrorTagEntity>) {
        if (rows.isEmpty()) return
        deleteTagsOf(rows.first().shelfKey, rows.map { it.targetId })
        insertRows(rows)
        if (tags.isNotEmpty()) insertTags(tags)
    }

    /**
     * 这批作品在库里已有的收藏序号。
     *
     * 增量维护会反复走到表头那些**早就镜像过**的作品，它们的 [BookmarkMirrorEntity.bookmarkSeq]
     * 必须原样保留 —— 重新分配等于把用户的收藏顺序打乱。所以每页写库前先查一次，
     * 命中的沿用旧序号，没命中的才是真·新收藏。
     */
    @Query("SELECT targetId, bookmarkSeq FROM bookmark_mirror_table WHERE shelfKey = :shelfKey AND targetId IN (:targetIds)")
    fun existingSeqs(shelfKey: String, targetIds: List<Long>): List<MirrorIdSeq>

    /** 全量重扫收尾：删掉本轮没再出现过的行 = 已经在别处取消了收藏。 */
    @Query("DELETE FROM bookmark_mirror_table WHERE shelfKey = :shelfKey AND generation < :generation")
    fun deleteStaleRows(shelfKey: String, generation: Int): Int

    @Query("DELETE FROM bookmark_mirror_tag_table WHERE shelfKey = :shelfKey AND targetId NOT IN (SELECT targetId FROM bookmark_mirror_table WHERE shelfKey = :shelfKey)")
    fun deleteOrphanTags(shelfKey: String): Int

    /** 本地取消收藏后即时抹掉（跨公开/悄悄两个书架，因为调用点未必知道它在哪边）。 */
    @Query("DELETE FROM bookmark_mirror_table WHERE ownerUid = :ownerUid AND contentType = :contentType AND targetId = :targetId")
    fun deleteTarget(ownerUid: Long, contentType: Int, targetId: Long): Int

    /**
     * 配套 [deleteTarget] 清标签。shelfKey 由调用方算好传进来（同一 uid + contentType 下
     * 的公开/悄悄两个书架），**不从状态表反查** —— 用户关掉某个书架的镜像后状态行就没了，
     * 反查会静默漏删，留下一堆没有主行的孤儿标签。
     */
    @Query("DELETE FROM bookmark_mirror_tag_table WHERE targetId = :targetId AND shelfKey IN (:shelfKeys)")
    fun deleteTargetTags(shelfKeys: List<String>, targetId: Long): Int

    /** 整架清空（换号、用户手动重建镜像）。 */
    @Query("DELETE FROM bookmark_mirror_table WHERE shelfKey = :shelfKey")
    fun clearShelfRows(shelfKey: String)

    @Query("DELETE FROM bookmark_mirror_tag_table WHERE shelfKey = :shelfKey")
    fun clearShelfTags(shelfKey: String)

    @Transaction
    fun clearShelf(shelfKey: String) {
        clearShelfRows(shelfKey)
        clearShelfTags(shelfKey)
    }

    // ─────────────────────────── 状态 ───────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertState(state: BookmarkMirrorStateEntity)

    @Query("SELECT * FROM bookmark_mirror_state_table WHERE shelfKey = :shelfKey LIMIT 1")
    fun findState(shelfKey: String): BookmarkMirrorStateEntity?

    @Query("SELECT * FROM bookmark_mirror_state_table")
    fun allStates(): List<BookmarkMirrorStateEntity>

    @Query("SELECT * FROM bookmark_mirror_state_table WHERE ownerUid = :ownerUid")
    fun observeStates(ownerUid: Long): Flow<List<BookmarkMirrorStateEntity>>

    @Query("DELETE FROM bookmark_mirror_state_table WHERE shelfKey = :shelfKey")
    fun deleteState(shelfKey: String)

    // ─────────────────────────── 读取 ───────────────────────────

    @Query("SELECT COUNT(*) FROM bookmark_mirror_table WHERE shelfKey = :shelfKey")
    fun countOf(shelfKey: String): Int

    @Query("SELECT COUNT(*) FROM bookmark_mirror_table WHERE shelfKey = :shelfKey")
    fun observeCount(shelfKey: String): Flow<Int>

    /**
     * 按账号（而不是按书架）观察行数变化。
     *
     * 页面上可以就地在「公开 / 悄悄」两个书架之间切换，按 shelfKey 订阅的话每切一次都要
     * 拆掉旧 Flow 再订一个新的；按 ownerUid 订一次就覆盖两边（Room 的失效通知本来就是
     * 表级的，粒度上没有任何损失）。
     */
    @Query("SELECT COUNT(*) FROM bookmark_mirror_table WHERE ownerUid = :ownerUid")
    fun observeOwnerCount(ownerUid: Long): Flow<Int>

    @Query("SELECT * FROM bookmark_mirror_table WHERE shelfKey = :shelfKey AND targetId = :targetId LIMIT 1")
    fun findRow(shelfKey: String, targetId: Long): BookmarkMirrorEntity?

    /**
     * 花式筛选的执行口。查询由 [BookmarkMirrorQuery] 拼出来（列名与顺序全是白名单常量，
     * 用户输入只经 `?` 绑定），这里只负责跑。
     */
    @RawQuery(observedEntities = [BookmarkMirrorEntity::class, BookmarkMirrorTagEntity::class])
    fun rawRows(query: SupportSQLiteQuery): List<BookmarkMirrorEntity>

    /** 同一套筛选条件下的命中总数（界面上的「共 N 件」）。 */
    @RawQuery(observedEntities = [BookmarkMirrorEntity::class, BookmarkMirrorTagEntity::class])
    fun rawCount(query: SupportSQLiteQuery): Int

    /** 标签云 / 作者云。SQL 同样由 [BookmarkMirrorQuery] 拼。 */
    @RawQuery(observedEntities = [BookmarkMirrorEntity::class, BookmarkMirrorTagEntity::class])
    fun rawTagFacets(query: SupportSQLiteQuery): List<BookmarkTagFacet>

    @RawQuery(observedEntities = [BookmarkMirrorEntity::class, BookmarkMirrorTagEntity::class])
    fun rawAuthorFacets(query: SupportSQLiteQuery): List<BookmarkAuthorFacet>

    /** 「我收藏过的年份」——年份筛选器的可选项，顺带给出每年多少件。 */
    @Query(
        "SELECT CAST(strftime('%Y', createDateMs / 1000, 'unixepoch') AS INTEGER) AS year, COUNT(*) AS hitCount " +
            "FROM bookmark_mirror_table WHERE shelfKey = :shelfKey AND createDateMs > 0 " +
            "GROUP BY year ORDER BY year DESC"
    )
    fun yearFacets(shelfKey: String): List<BookmarkYearFacet>
}

/** 年份 facet 投影。 */
data class BookmarkYearFacet(val year: Int, val hitCount: Int)
