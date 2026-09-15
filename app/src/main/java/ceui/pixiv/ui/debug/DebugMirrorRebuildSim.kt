package ceui.pixiv.ui.debug

import android.content.Context
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.ceil

/**
 * 【临时·调试】「重建本地镜像」的**模拟版**：不拉 pixiv，只把当前书架的行整批搬到一根隐藏的
 * 备份 key 下，并把状态改写成「刚被重建、还没开始回填」的样子 —— 于是页面立刻进入
 * 「补齐中」（进度条 + 筛选/排序/搜索被收走 + 滑到底还能继续续页）。
 *
 * 然后按节拍把行**一批批搬回来**，看起来就是引擎在真的回填：计数从 0 一路涨到原值、列表一批批
 * 续上、跑完自动解锁搜索/筛选。全程零请求，只是把本地已有的行换 key —— 数字和列表都不是假数据，
 * 页面上每一条都是本地库里的真行，所以「涨到 100% 就正好全部可见」这件事是自洽的。
 *
 * 为什么搬 key 而不是删行：真删就得重新拉几十分钟 pixiv（也违背"不真的拉 pixiv"）。行 + 标签行
 * + 原来的 state 行三样都搬过去 —— `shelfKey` 就是这些表的分区列，搬完之后对真书架来说就是
 * 「空的」，而对引擎来说备份 key 是一根它永远认不出的书架：
 *
 * - `BookmarkMirrorService.pickJob` 只按 `MirrorPhase` 里那几个已知 phase 挑活，[SIM_PHASE]
 *   故意取一个**没有代码会选中的值** —— 引擎一行都不用改，进程重启也不会去拉；
 * - 备份 state 行的 key 解析不出 [BookmarkShelf]（多一段前缀，`parse` 只认 3 段），所以它同样
 *   不会被选中；再把两个时间戳推到当下，连"到期维护 / 到期重扫"那两条也匹配不上；
 * - 备份行保留 ownerUid，于是 `observeOwnerCount(uid)` 会把它算进去 —— 那一路只当"库又变了"的
 *   触发器用，页面上的数字全都按 shelfKey 查，不会受影响。
 *
 * ⚠️ **用完即删**，四处一起删：
 * 1. 本文件；
 * 2. `BookmarkLibraryUi.setUpMenu` 里 `MENU_REBUILD_SIM` 那段（连同 `BuildConfig.DEBUG` 门）；
 * 3. `BookmarkLibraryUi` 的 `toggleSimulatedRebuild()` / `renderRebuildSimItem()` 及后者的调用；
 * 4. 两条文案 `bookmark_library_rebuild_sim` / `bookmark_library_rebuild_sim_restore`（7 套 locale）。
 */
object DebugMirrorRebuildSim {

    private const val TAG = "BookmarkMirror/Sim"

    /**
     * 模拟态用的 phase 值。**故意不是** `MirrorPhase` 里的任何一个：引擎的 pickJob 只按已知
     * phase 挑活，于是这个书架在它眼里等于不存在。取负数是为了在日志里一眼认出是人为塞的
     * （`MirrorPhase.name()` 会打 `UNKNOWN(-1)`）。
     */
    private const val SIM_PHASE = -1

    /** 备份用的书架 key 前缀。多一段前缀 → `BookmarkShelf.parse` 必定返回 null。 */
    private const val BACKUP_PREFIX = "sim-bak:"

    /**
     * 假回填的节拍：所有书架都跑 [TICK_COUNT] 拍、每拍 [TICK_INTERVAL_MS] 毫秒 —— 演示时长固定
     * ≈9 秒，跟书架有几百行还是几万行无关。（每拍的批量因此按总行数算。）
     */
    private const val TICK_COUNT = 10
    private const val TICK_INTERVAL_MS = 900L

    /** 搬 key 是纯 SQL 的 UPDATE：几十万行也是常数级耗时，不把任何行读进内存。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在假回填的那条协程（有的话）。重复触发时先掐掉老的，避免两条协程抢着搬。 */
    private var runJob: Job? = null

    /** 当前书架是不是处于「模拟重建过」的状态（收藏库用它决定菜单项是"模拟"还是"还原"）。 */
    fun isSimulated(state: BookmarkMirrorStateEntity?): Boolean = state?.phase == SIM_PHASE

    /**
     * 模拟一次重建：状态改成"刚重建"、行搬到备份 key，然后分 [TICK_COUNT] 拍搬回来。
     *
     * [onChanged] 在每搬完一拍、以及全部结束后回调一次 —— 收藏库拿它去 `refreshCounts()`，
     * 让计数与列表跟着涨（不依赖 Room 失效的时机，节奏更稳）。只对**已补齐完成**的书架生效。
     */
    @JvmStatic
    fun simulate(context: Context, shelf: BookmarkShelf, onChanged: () -> Unit) {
        runJob?.cancel()
        runJob = scope.launch {
            val total = performSimulate(context, shelf) ?: return@launch
            onChanged()
            val chunk = ceil(total.toDouble() / TICK_COUNT).toInt().coerceAtLeast(1)
            repeat(TICK_COUNT) {
                if (countRemaining(context, shelf) <= 0) return@repeat
                moveChunk(context, shelf, chunk)
                onChanged()
                delay(TICK_INTERVAL_MS)
            }
            // 跑完 = 行全回来了 → 状态行复原（firstCompletedAt 有值）→ 搜索/筛选自动解锁。
            performRestore(context, shelf)
            onChanged()
        }
    }

    /** 还原：先掐掉在跑的假回填，再把行与状态整体复原。 */
    @JvmStatic
    fun restore(context: Context, shelf: BookmarkShelf, onChanged: () -> Unit = {}) {
        runJob?.cancel()
        runJob = scope.launch {
            performRestore(context, shelf)
            onChanged()
        }
    }

    /**
     * 真正干活的那半，跑在 [scope] 的 IO 线程上 —— DAO 与 `execSQL` 都是阻塞 API，不能上主线程。
     * 刻意**不是** suspend：整段没有任何挂起点（搬 key 是常数级 SQL），挂着 suspend 只是摆设。
     *
     * @return 搬走的总行数；书架不满足条件（没补齐完成）时返回 null 表示拒绝执行。
     */
    private fun performSimulate(context: Context, shelf: BookmarkShelf): Int? {
        val dao = AppDatabase.getAppDatabase(context).bookmarkMirrorDao()
        val saved = dao.findState(shelf.key)
        if (saved == null || !saved.isFirstSyncDone) {
            Timber.tag(TAG).w("[%s] 只支持模拟「已补齐完成」的书架，已忽略", shelf.label)
            return null
        }
        val total = dao.countOf(shelf.key)
        val backupKey = BACKUP_PREFIX + shelf.key
        val now = System.currentTimeMillis()
        // ① 先立起「刚被重建」的状态，再搬行 —— 这样页面上的顺序是"先变成补齐中，再变空"，
        //    不会出现"已补齐却 0 行"的中间态。generation 归零 = 在飞的那一页会被 runOnePage
        //    的 generation 守卫丢掉，与真实重建走的是同一条保护。
        dao.upsertState(
            saved.copy(
                phase = SIM_PHASE,
                nextUrl = null,
                generation = 0,
                nextBackfillSeq = 0L,
                headSeqCursor = 0L,
                headBlockCeiling = 0L,
                pagesThisRun = 0,
                itemsThisRun = 0,
                firstCompletedAt = 0L,
                lastError = null,
                lastErrorAt = 0L,
                consecutiveFailures = 0,
                cooldownUntil = 0L,
                updatedAt = now,
            )
        )
        // ② 原来的状态行整根搬到备份 key 下：还原时直接搬回来，不用猜当时是什么 phase。
        //    两个时间戳推到当下，让它在 pickJob 的"到期维护/到期重扫"两条里也匹配不上。
        dao.upsertState(
            saved.copy(
                shelfKey = backupKey,
                lastSyncedAt = now,
                lastFullSweepAt = now,
                updatedAt = now,
            )
        )
        // ③ 行与标签行换成备份 key
        moveShelf(context, fromKey = shelf.key, toKey = backupKey)
        Timber.tag(TAG).i(
            "[%s] 模拟重建：%d 行搬到 %s，phase 改为 %d（不拉 pixiv）",
            shelf.label, total, backupKey, SIM_PHASE,
        )
        return total
    }

    /** 还原：行搬回来 + 原来的状态行复原。 */
    private fun performRestore(context: Context, shelf: BookmarkShelf) {
        val dao = AppDatabase.getAppDatabase(context).bookmarkMirrorDao()
        val backupKey = BACKUP_PREFIX + shelf.key
        val saved = dao.findState(backupKey)
        if (saved == null) {
            Timber.tag(TAG).w("[%s] 没有模拟前的状态行，无法还原", shelf.label)
            return
        }
        // 先搬行、再恢复状态：中间那一瞬是"补齐中且已有 N 行"，页面会自行对齐
        // （emptyButStored / 条数对账两条路都已存在）。
        moveShelf(context, fromKey = backupKey, toKey = shelf.key)
        dao.upsertState(saved.copy(shelfKey = shelf.key, updatedAt = System.currentTimeMillis()))
        dao.deleteState(backupKey)
        Timber.tag(TAG).i(
            "[%s] 模拟重建已还原：%d 行搬回，状态复原",
            shelf.label, dao.countOf(shelf.key),
        )
    }

    /** 还剩多少行没搬回来 —— 假回填的进度就是它。 */
    private fun countRemaining(context: Context, shelf: BookmarkShelf): Int =
        AppDatabase.getAppDatabase(context).bookmarkMirrorDao().countOf(BACKUP_PREFIX + shelf.key)

    /**
     * 搬回**最新的一批**（`bookmarkSeq` 大的先回）。为什么按这个序：新版列表在补齐期锁死在
     * `BOOKMARK_NEWEST`（`bookmarkSeq DESC`），最新的一批先可见，页面上的列表就是"从新往旧"
     * 一页页续上，和真实回填的观感一致。
     *
     * @param chunk 这一拍最多搬多少行；不足则把剩下的全搬完。
     */
    private fun moveChunk(context: Context, shelf: BookmarkShelf, chunk: Int) {
        val backupKey = BACKUP_PREFIX + shelf.key
        val boundary = findBoundarySeq(context, backupKey, chunk)
        val db = AppDatabase.getAppDatabase(context).openHelper.writableDatabase
        // 标签行先走：它的条件是"mirror 表里那批 row 的 targetId"，必须赶在 mirror 行换 key 之前。
        if (boundary == null) {
            moveShelf(context, fromKey = backupKey, toKey = shelf.key)
            return
        }
        db.execSQL(
            "UPDATE bookmark_mirror_tag_table SET shelfKey = ? WHERE shelfKey = ? " +
                "AND targetId IN (SELECT targetId FROM bookmark_mirror_table " +
                "WHERE shelfKey = ? AND bookmarkSeq >= ?)",
            arrayOf<Any?>(shelf.key, backupKey, backupKey, boundary),
        )
        db.execSQL(
            "UPDATE bookmark_mirror_table SET shelfKey = ? WHERE shelfKey = ? AND bookmarkSeq >= ?",
            arrayOf<Any?>(shelf.key, backupKey, boundary),
        )
    }
/**
     * 找出「下一批搬回」的边界 bookmarkSeq：备份 key 下第 `chunk` 大（0 基）的那一行。
     *
     * 刻意把 `use` 当**语句**而不是 `try` 的表达式体 —— 写成表达式时 Kotlin 会把 `use` 的泛型
     * `R` 和 try/catch 的 `TRY_CALL` 撞在一起，报
     * 「Argument type mismatch: ... 'R of T.use' ... 'K of TRY_CALL'」。拆开后就两条路都不犯。
     *
     * @return 边界 seq；查询/游标出问题时返回 null（调用方整批搬回兜底）。
     */
    private fun findBoundarySeq(context: Context, backupKey: String, chunk: Int): Long? {
        val db = AppDatabase.getAppDatabase(context).openHelper.writableDatabase
        var boundary: Long? = null
        try {
            db.query(
                "SELECT bookmarkSeq FROM bookmark_mirror_table WHERE shelfKey = ? " +
                    "ORDER BY bookmarkSeq DESC LIMIT 1 OFFSET ?",
                arrayOf<Any?>(backupKey, (chunk - 1).toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) boundary = cursor.getLong(0)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[%s] 模拟回填取边界失败，整批搬回", backupKey)
        }
        return boundary
    }

    /** 行 + 标签行一起换 key：标签倒排表不跟着走的话，还原后按标签筛会空。 */
    private fun moveShelf(context: Context, fromKey: String, toKey: String) {
        val db = AppDatabase.getAppDatabase(context).openHelper.writableDatabase
        db.execSQL(
            "UPDATE bookmark_mirror_table SET shelfKey = ? WHERE shelfKey = ?",
            arrayOf<Any?>(toKey, fromKey),
        )
        db.execSQL(
            "UPDATE bookmark_mirror_tag_table SET shelfKey = ? WHERE shelfKey = ?",
            arrayOf<Any?>(toKey, fromKey),
        )
    }
}
