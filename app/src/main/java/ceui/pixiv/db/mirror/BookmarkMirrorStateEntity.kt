package ceui.pixiv.db.mirror

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个书架的镜像同步状态。**一个书架一行，进程被杀就靠它续上**。
 *
 * 断点续传的全部信息都在这里，而且是**每翻一页就落一次盘**：杀进程、崩溃、掉网、
 * 用户手动清后台，下次启动读回 [nextUrl] 从那一页接着翻，绝不从头再来一遍
 * （从头再来一遍不仅慢，更要命的是白白多打几百次请求去撞 pixiv 的频控）。
 *
 * [phase] 的状态机：
 * ```
 *   NEVER ──开始回填──▶ BACKFILLING ──走到 next_url 为空──▶ SYNCED
 *                          ▲   │                              │
 *                          └───┘ 每页落盘 nextUrl，杀进程后从这里续          │
 *                                                                          │
 *   SYNCED ──增量维护（只走表头几页）──▶ SYNCED                            │
 *   SYNCED ──到期全量重扫（发现别处取消的收藏）──▶ RESWEEPING ─────────────┘
 * ```
 * [firstCompletedAt] > 0 就是「同步完成过一次」的判据：从此之后引擎只做维护，
 * 界面也可以放心地把本地表当作**完整**的收藏列表来排序/筛选。
 */
@Entity(tableName = "bookmark_mirror_state_table")
data class BookmarkMirrorStateEntity(
    @PrimaryKey val shelfKey: String,
    val ownerUid: Long,
    val contentType: Int,
    /** 见 [MirrorRestrict.code]。列名避开 SQLite 的 RESTRICT 关键字。 */
    val restrictCode: Int,

    /** 见 [MirrorPhase]。 */
    val phase: Int,

    /**
     * 续传游标：下一页的 `next_url`（内含 pixiv 的 `max_bookmark_id` keyset 游标，
     * 对期间新增/取消的收藏是稳定的）。null = 从第一页开始。
     */
    val nextUrl: String?,

    /** 当前（或最近一次）全量扫描的代号，收尾时用来删失联行。 */
    val generation: Int,

    /** 全量回填下一个要分配的序号（0 起逐条递减），见 [BookmarkMirrorEntity.bookmarkSeq]。 */
    val nextBackfillSeq: Long,

    /**
     * 增量维护给**新收藏**分配序号的游标（在当前号段内**向下**发号）。
     *
     * 为什么要「号段 + 向下发号」这么绕：维护是从表头往后翻的，所以**先遇到的更新**，
     * 序号必须**更大**；而我们又是一页一落盘（随时可能被杀），不能等整轮跑完再统一编号。
     * 于是每轮开跑时先占一整段号（[headBlockCeiling] 抬高一个 [HEAD_SEQ_BLOCK]），
     * 段内从顶往下发：先遇到的拿大号，页与页之间也自然递减，且这一轮的全部号段都高于
     * 上一轮 —— 无论从哪一页被杀、从哪一页续上，顺序都不会乱。
     */
    val headSeqCursor: Long,
    /** 当前号段的顶。下一轮从 `headBlockCeiling + HEAD_SEQ_BLOCK` 重新开段。 */
    val headBlockCeiling: Long,

    /** 本轮已翻页数 / 已见条目数，纯观测用（日志与状态条）。 */
    val pagesThisRun: Int,
    val itemsThisRun: Int,

    /** 首次全量完成的时间戳；> 0 = 「同步完成过一次」。 */
    val firstCompletedAt: Long,
    /** 最近一次成功跑完（回填完成 / 维护完成）的时间。 */
    val lastSyncedAt: Long,
    /** 最近一次全量重扫完成的时间，决定下次何时再重扫。 */
    val lastFullSweepAt: Long,

    /** 最近一次失败的时间与原因（人类可读，只进日志与调试页，不做协议）。 */
    val lastErrorAt: Long,
    val lastError: String?,
    /** 连续失败次数，驱动指数退避；成功一次即清零。 */
    val consecutiveFailures: Int,

    /**
     * 被限流后的解冻时刻（epoch ms）。撞过 429 就把整个引擎冻到这个点之后，
     * 并且**这一轮之后的每页间隔也会被永久放大**（见 `BookmarkMirrorEngine`）。
     */
    val cooldownUntil: Long,

    val updatedAt: Long,
) {
    val shelf: BookmarkShelf?
        get() = BookmarkShelf.parse(shelfKey)

    val isFirstSyncDone: Boolean get() = firstCompletedAt > 0L
}

/** [BookmarkMirrorStateEntity.phase] 的取值。入库值不能改。 */
object MirrorPhase {
    /** 从未开始过。 */
    const val NEVER = 0
    /** 首次全量回填进行中（可能已经跑了一半，[BookmarkMirrorStateEntity.nextUrl] 是断点）。 */
    const val BACKFILLING = 1
    /** 已完成过一次全量，之后只做增量维护。 */
    const val SYNCED = 2
    /** 到期全量重扫进行中（为了发现在别处取消的收藏）。表里的行照常可读。 */
    const val RESWEEPING = 3

    fun name(phase: Int): String = when (phase) {
        NEVER -> "NEVER"
        BACKFILLING -> "BACKFILLING"
        SYNCED -> "SYNCED"
        RESWEEPING -> "RESWEEPING"
        else -> "UNKNOWN($phase)"
    }
}
