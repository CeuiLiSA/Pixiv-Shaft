package ceui.lisa.database

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.pixiv.download.importer.NameParser
import ceui.pixiv.download.importer.PageBaseInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * v41 的 page 列一次性存量回填。
 *
 * v41 迁移只是 `ADD COLUMN page ... DEFAULT -1`（O(1)，不重写行）。这里在启动后后台把
 * 老行的页码从 fileName 里解析出来补上，让「已存在则跳过」和详情页复用本地文件能按
 * `(illustId, page)` 查询命中老记录，而不是只对 v41 之后新下载的生效。
 *
 * 复用 [NameParser] + [PageBaseInference] —— 和 `DownloadImporter` 扫盘时用的是同一套
 * 反向模板匹配和页码基准推断，不写第二份文件名解析。
 *
 * **按作品分批，不是按行分批。** 文件名里的 `p1` 到底是第 0 页还是第 1 页，取决于当时
 * 的页码基准设置，单看一个文件名判不出来；必须拿同一作品所有页一起看（出现过 `p0`
 * 就是 0 基，最小是 `p1` 就是 1 基）。逐行猜的话，基准判错会把第 N 页的本地图错配到
 * 第 N±1 页 —— 详情页复用本地文件时直接显示错图，比"查不到"还糟。
 *
 * 每批作品一个事务；中途被杀不置标志，下次启动从剩余的续跑（幂等）。
 * 解析不出页码的行写 [UNPARSEABLE] 而不是留 -1 —— 留 -1 会让下一批又捞到同一批行，
 * 死循环。
 */
object DownloadPageBackfill {

    private const val DONE_KEY = "download_page_backfill_v1"

    /** 每批取多少个**作品**（不是行）。 */
    private const val BATCH = 50

    /** 试过了但文件名里解析不出页码。与"还没试过"的 -1 必须区分。 */
    const val UNPARSEABLE = -2

    /** 等 [DownloadIdBackfill] 把 illustId 补出来的轮询间隔。 */
    private const val ID_BACKFILL_POLL_MS = 2_000L

    /** 最多等多久 —— 超时就不置标志，下次启动重来，别让协程挂着不走。 */
    private const val MAX_WAIT_FOR_ID_BACKFILL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun isComplete(): Boolean =
        runCatching { Shaft.getMMKV().decodeBool(DONE_KEY, false) }.getOrDefault(false)

    /** 启动时调一次；已完成直接返回，否则后台分批回填。 */
    @JvmStatic
    fun runIfNeeded(context: Context) {
        if (isComplete()) return
        val app = context.applicationContext
        scope.launch {
            try {
                val db = AppDatabase.getAppDatabase(app)
                val dao = db.downloadDao()
                // NameParser 会读一次当前下载配置来生成候选模板，整轮回填复用同一个
                // 实例 —— 命中上浮的缓存也就跨批次生效了。
                val parser = NameParser.create()
                var works = 0
                var rows = 0
                var resolved = 0
                var waitedForIdBackfill = 0L
                var complete = true
                while (true) {
                    val illustIds = dao.getIllustIdsNeedingPageBackfill(BATCH)
                    if (illustIds.isEmpty()) {
                        // "捞不到待回填的作品"不等于"回填完了"。本查询要求 illustId > 0，
                        // 而 v38 之前的存量行 illustId 还是 0，得等 DownloadIdBackfill 补上
                        // 才看得见 —— 两个回填是在 Shaft.onCreate 里同时起的协程，从 <=4.5.7
                        // 一步升上来的用户（issue #953 的正主）第一轮几乎必然什么都捞不到。
                        // 这时候要是直接置一次性标志，那批行就永久卡在 page = -1，「已存在则
                        // 跳过」和详情页复用又退回按文件名查 —— 正好是本 issue 要修的东西。
                        if (DownloadIdBackfill.isComplete()) break
                        if (waitedForIdBackfill >= MAX_WAIT_FOR_ID_BACKFILL_MS) {
                            // id 回填自己也中断了（它同样不置标志，下次启动续跑）。
                            // 这里跟着不置标志退出，下次启动一起重来。
                            Timber.tag(TAG).w("等 illustId 回填超时，本轮不收工，下次启动续跑")
                            complete = false
                            break
                        }
                        delay(ID_BACKFILL_POLL_MS)
                        waitedForIdBackfill += ID_BACKFILL_POLL_MS
                        continue
                    }
                    val rowsBefore = rows
                    // 一批作品共用一个事务。下载库以单页作品为主，如果每个作品各开
                    // 一个事务，3 万行就会放大成 3 万次 BEGIN/COMMIT，启动后台回填
                    // 会持续抢占 I/O 和写锁。解析仍按作品分别做，写入原子性提升到整批。
                    val updates = ArrayList<Pair<String, Int>>()
                    for (illustId in illustIds) {
                        val fileNames = dao.getFileNamesNeedingPageBackfill(illustId)
                        if (fileNames.isEmpty()) continue
                        // 先整批解析，拿到这个作品全部页的字面页码，才能定基准。
                        val parsed = fileNames.map { it to parser.parse(it) }
                        val base = PageBaseInference.infer(parsed.mapNotNull { it.second })
                        for ((fileName, hit) in parsed) {
                            val page = if (hit == null || isNovel(fileName)) {
                                UNPARSEABLE
                            } else {
                                PageBaseInference.toZeroBasedOrNull(hit.printedPage, base)
                                    ?: UNPARSEABLE
                            }
                            updates += fileName to page
                        }
                    }
                    if (updates.isNotEmpty()) {
                        db.runInTransaction {
                            for ((fileName, page) in updates) {
                                dao.setDownloadPage(fileName, page)
                            }
                        }
                        rows += updates.size
                        resolved += updates.count { it.second >= 0 }
                    }
                    works += illustIds.size
                    // 这一批一行都没动却又查得到待回填的作品 —— 只可能是并发删除之类的
                    // 竞态。再转一圈也是同样结果，别死循环；但也**不能**当成跑完了置标志，
                    // 否则剩下的行永久卡在 page = -1。下次启动续跑。
                    if (rows == rowsBefore) {
                        Timber.tag(TAG).w("batch made no progress, stopping")
                        complete = false
                        break
                    }
                }
                if (complete) {
                    Shaft.getMMKV().encode(DONE_KEY, true)
                }
                Timber.tag(TAG).i(
                    "page backfill %s, works=%d rows=%d resolved=%d",
                    if (complete) "done" else "partial", works, rows, resolved,
                )
            } catch (t: Throwable) {
                // 未置标志 → 下次启动续跑；期间按 (illustId, page) 查不到的行会退回
                // fileName 主键查询，正确性不受影响。
                Timber.tag(TAG).w(t, "page backfill interrupted, resume next launch")
            }
        }
    }

    /**
     * 小说记录跟这一列无关（没有"第几页"的概念），一律标 [UNPARSEABLE]。
     * 不这么做的话，`pixiv_shaft_novel_12345` 会被启发式抠出个 id、判成第 0 页写进去 ——
     * 万一有插画的 id 恰好等于这个小说 id，详情页按 (illustId, page) 查就会翻出小说的
     * txt 当图片。
     */
    private fun isNovel(fileName: String): Boolean = fileName.contains(Params.NOVEL_KEY)

    private const val TAG = "DL-PAGE-BACKFILL"
}
