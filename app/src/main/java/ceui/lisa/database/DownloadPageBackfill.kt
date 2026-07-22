package ceui.lisa.database

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.pixiv.download.importer.NameParser
import ceui.pixiv.download.importer.PageBaseInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * 每个作品一个事务；中途被杀不置标志，下次启动从剩余的续跑（幂等）。
 * 解析不出页码的行写 [UNPARSEABLE] 而不是留 -1 —— 留 -1 会让下一批又捞到同一批行，
 * 死循环。
 */
object DownloadPageBackfill {

    private const val DONE_KEY = "download_page_backfill_v1"

    /** 每批取多少个**作品**（不是行）。 */
    private const val BATCH = 50

    /** 试过了但文件名里解析不出页码。与"还没试过"的 -1 必须区分。 */
    const val UNPARSEABLE = -2

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
                while (true) {
                    val illustIds = dao.getIllustIdsNeedingPageBackfill(BATCH)
                    if (illustIds.isEmpty()) break
                    val rowsBefore = rows
                    for (illustId in illustIds) {
                        val fileNames = dao.getFileNamesNeedingPageBackfill(illustId)
                        if (fileNames.isEmpty()) continue
                        // 先整批解析，拿到这个作品全部页的字面页码，才能定基准。
                        val parsed = fileNames.map { it to parser.parse(it) }
                        val base = PageBaseInference.infer(parsed.mapNotNull { it.second })
                        db.runInTransaction {
                            for ((fileName, hit) in parsed) {
                                val page = if (hit == null || isNovel(fileName)) {
                                    UNPARSEABLE
                                } else {
                                    PageBaseInference.toZeroBased(hit.printedPage, base)
                                }
                                if (page >= 0) resolved++
                                dao.setDownloadPage(fileName, page)
                            }
                        }
                        rows += fileNames.size
                    }
                    works += illustIds.size
                    // 这一批一行都没动却又查得到待回填的作品 —— 只可能是并发删除之类的
                    // 竞态。再转一圈也是同样结果，直接收工，别死循环。
                    if (rows == rowsBefore) {
                        Timber.tag(TAG).w("batch made no progress, stopping")
                        break
                    }
                }
                Shaft.getMMKV().encode(DONE_KEY, true)
                Timber.tag(TAG).i(
                    "page backfill done, works=%d rows=%d resolved=%d", works, rows, resolved,
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
