package ceui.pixiv.db

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.utils.Local
import ceui.pixiv.api.Client
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.HistoryReportBody
import ceui.pixiv.shaftapi.HistoryReportItem
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开启云同步时,把存量本地浏览历史一次性回填到云端(#989)。此前打开同步只影响之后的
 * 双写([HistoryReporter]),开同步前攒下的本地记录永远只在本地——换设备就看不见了。
 *
 * 每条带真实浏览时间(viewed_at),服务端只在「更新的浏览」时才覆盖,所以:
 * - 回填不会把云端顺序刷成「刚刚看过」;
 * - 整个回填幂等,失败中止后下次从头重推无害(重复条目在服务端是 no-op)。
 *
 * 完成标记([ceui.lisa.utils.Settings.cloudHistoryBackfillDoneUid])是**每设备一次**:
 * 本地历史表不分账号,记不住每行是谁登录时浏览的,同一台设备反复对不同 uid 全量重推
 * 既浪费限流额度,也会把 A 账号攒的历史反复灌进 B 的云端。标记在两处清零重跑:
 * 关闭云同步(重开时补传关同步期间攒的记录)、导入历史备份(把导入的行推上云端)。
 * 中途失败不记标记,下次触发(开开关 / 打开浏览历史页 / 导入)重试。
 *
 * 已知取舍:回填运行中被用户删除的条目,可能已被读进内存批次而被重新推上云端
 * ——删除语义的真解是 tombstone(#989 长期项),这里不做窗口内的存在性复查。
 */
object HistoryBackfill {

    /** 每次读库的页大小;逐页推送,内存里任何时刻只有一页(对齐 #981 的教训)。 */
    private const val DB_PAGE = 200

    /** 服务端批量上限(pixshaft-api HISTORY_MAX_BATCH)。 */
    private const val MAX_BATCH = 100

    /**
     * 服务端每 uid 每 target_type 只保留最新 1000 条(HISTORY_MAX_PER_TYPE),多推的很快
     * 会被 purge 掉,纯属浪费限流额度——本地按 time 倒序推到上限为止。注意本地 type=0
     * 混装 illust+manga 两个服务端类型,所以那一档的封顶要按两类算(见 [run])。
     */
    private const val MAX_PER_TYPE = 1000

    /** 批间限速:服务端 historyWrite 限流 60 次/min,留出浏览双写的余量。 */
    private const val BATCH_DELAY_MS = 1_500L

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "HistoryBackfill coroutine error (swallowed)") },
    )

    /** 条件不满足/这台设备已回填过/正在跑 → 什么都不做。任何失败都不影响调用方。 */
    fun maybeSchedule() {
        if (!HistoryReporter.cloudSyncAllowed()) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        // 每设备一次(!=0 即已跑过,存的是当时的 uid);清零重跑的两个入口见类注释
        if (Shaft.sSettings.cloudHistoryBackfillDoneUid != 0L) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                run(uid)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun run(uid: Long) {
        val db = AppDatabase.getAppDatabase(Shaft.getContext())
        var pushed = 0

        suspend fun push(items: List<HistoryReportItem>): Boolean {
            if (items.isEmpty()) return true
            // 中途关同步/换账号 → 立刻中止,绝不把 A 账号攒的历史推进 B 的云端
            if (!HistoryReporter.cloudSyncAllowed() || SessionManager.loggedInUid != uid) return false
            return try {
                Client.pixshaft.reportHistory(uid, HistoryReportBody(items))
                pushed += items.size
                delay(BATCH_DELAY_MS)
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                Timber.w(ex, "history backfill batch failed (aborting, will retry on next trigger)")
                false
            }
        }

        // 插画/漫画(type=0,对应服务端 illust+manga 两类 → 封顶×2) 与小说(type=1):
        // keyset 分页按 time 严格递减往老走。before 每页严格变小,天然免疫回填期间
        // 新浏览写入造成的位移漏行(LIMIT/OFFSET 会因表头插行把页边界的老记录挤过去)。
        for ((type, cap) in listOf(0 to MAX_PER_TYPE * 2, 1 to MAX_PER_TYPE)) {
            var before = Long.MAX_VALUE
            var taken = 0
            while (taken < cap) {
                val page = db.downloadDao().getViewHistoryByTypeBefore(type, before, DB_PAGE)
                if (page.isEmpty()) break
                taken += page.size
                before = page.last().time
                for (batch in page.mapNotNull { it.toHistoryReportItem() }.chunked(MAX_BATCH)) {
                    if (!push(batch)) return
                }
                if (page.size < DB_PAGE) break
            }
        }
        // 用户历史(general_table),同样 keyset 分页 + 封顶
        var before = Long.MAX_VALUE
        var taken = 0
        while (taken < MAX_PER_TYPE) {
            val page = db.generalDao().getByRecordTypeBefore(RecordType.VIEW_USER_HISTORY, before, DB_PAGE)
            if (page.isEmpty()) break
            taken += page.size
            before = page.last().updatedTime
            for (batch in page.mapNotNull { it.toUserHistoryReportItem() }.chunked(MAX_BATCH)) {
                if (!push(batch)) return
            }
            if (page.size < DB_PAGE) break
        }

        Shaft.sSettings.cloudHistoryBackfillDoneUid = uid
        Local.setSettings(Shaft.sSettings)
        Timber.i("history backfill done for uid=%d (%d items)", uid, pushed)
    }
}

/**
 * illust_table 行 → 云端上报条目。payload 必须是 JSON object(服务端整批校验,一条不合法
 * 会 400 掉整批),坏行直接跳过;viewed_at 用本地浏览时间,0/负值不带(服务端会拒)。
 */
fun IllustHistoryEntity.toHistoryReportItem(): HistoryReportItem? {
    if (illustID == 0 || illustJson.isNullOrEmpty()) return null
    val tree = runCatching { JsonParser.parseString(illustJson) }.getOrNull()
        ?.takeIf { it.isJsonObject } ?: return null
    val targetType = if (type == 1) {
        "novel"
    } else {
        // 直接从已解析的 tree 读 type 字段,不再整只反序列化 Illust 解一遍同样的 JSON
        val workType = runCatching {
            tree.asJsonObject.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
        if (workType == "manga") "manga" else "illust"
    }
    return HistoryReportItem(targetType, illustID.toLong(), tree, time.takeIf { it > 0 })
}

/** general_table 的用户历史行 → 云端上报条目。约束同 [toHistoryReportItem]。 */
fun GeneralEntity.toUserHistoryReportItem(): HistoryReportItem? {
    if (id == 0L || json.isEmpty()) return null
    val tree = runCatching { JsonParser.parseString(json) }.getOrNull()
        ?.takeIf { it.isJsonObject } ?: return null
    return HistoryReportItem("user", id, tree, updatedTime.takeIf { it > 0 })
}
