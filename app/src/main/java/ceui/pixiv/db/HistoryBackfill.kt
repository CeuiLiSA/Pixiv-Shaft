package ceui.pixiv.db

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Local
import ceui.loxia.Client
import ceui.loxia.HistoryReportBody
import ceui.loxia.HistoryReportItem
import ceui.pixiv.session.SessionManager
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
 * 成功跑完记 [ceui.lisa.utils.Settings.cloudHistoryBackfillDoneUid],同一账号不再重推;
 * 中途失败不记,下次触发([CloudHistoryConsent] 开开关 / 打开浏览历史页)重试。
 */
object HistoryBackfill {

    /** 每次读库的页大小;逐页推送,内存里任何时刻只有一页(对齐 #981 的教训)。 */
    private const val DB_PAGE = 200

    /** 服务端批量上限(pixshaft-api HISTORY_MAX_BATCH)。 */
    private const val MAX_BATCH = 100

    /**
     * 服务端每 uid 每类只保留最新 1000 条(HISTORY_MAX_PER_TYPE),多推的很快会被
     * purge 掉,纯属浪费限流额度——本地按 time 倒序最多推这么多。
     */
    private const val MAX_PER_TYPE = 1000

    /** 批间限速:服务端 historyWrite 限流 60 次/min,留出浏览双写的余量。 */
    private const val BATCH_DELAY_MS = 1_500L

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "HistoryBackfill coroutine error (swallowed)") },
    )

    /** 条件不满足/已回填过/正在跑 → 什么都不做。任何失败都不影响调用方。 */
    fun maybeSchedule() {
        if (!Shaft.sSettings.isCloudHistorySync || !Shaft.sSettings.isCloudHistoryConsentShown) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        if (Shaft.sSettings.cloudHistoryBackfillDoneUid == uid) return
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
            if (!Shaft.sSettings.isCloudHistorySync || SessionManager.loggedInUid != uid) return false
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

        // 插画/漫画(type=0) 与小说(type=1):time 倒序分页,各按云端保留上限封顶
        for (type in intArrayOf(0, 1)) {
            var offset = 0
            while (offset < MAX_PER_TYPE) {
                val page = db.downloadDao().getViewHistoryByType(type, DB_PAGE, offset)
                if (page.isEmpty()) break
                for (batch in page.mapNotNull { it.toHistoryReportItem() }.chunked(MAX_BATCH)) {
                    if (!push(batch)) return
                }
                if (page.size < DB_PAGE) break
                offset += page.size
            }
        }
        // 用户历史(general_table),同样封顶
        var offset = 0
        while (offset < MAX_PER_TYPE) {
            val page = db.generalDao().getByRecordType(RecordType.VIEW_USER_HISTORY, offset, DB_PAGE)
            if (page.isEmpty()) break
            for (batch in page.mapNotNull { it.toUserHistoryReportItem() }.chunked(MAX_BATCH)) {
                if (!push(batch)) return
            }
            if (page.size < DB_PAGE) break
            offset += page.size
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
        val ib = runCatching { Shaft.sGson.fromJson(illustJson, IllustsBean::class.java) }.getOrNull()
        if (ib?.type == "manga") "manga" else "illust"
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
