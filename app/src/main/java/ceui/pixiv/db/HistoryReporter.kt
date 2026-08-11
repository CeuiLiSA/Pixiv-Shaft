package ceui.pixiv.db

import ceui.lisa.activities.Shaft
import ceui.loxia.Client
import ceui.loxia.HistoryReportBody
import ceui.loxia.HistoryReportItem
import ceui.loxia.SyncPrefBody
import ceui.pixiv.session.SessionManager
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Buffers browse-history visits and flushes them to pixshaft-api in batches, so
 * opening an artwork never blocks on a network call. Best-effort: a few of the
 * most-recent visits may be lost on a hard process kill, which is fine for
 * history. Keyed by the logged-in viewer's uid (skipped when logged out).
 */
object HistoryReporter {

    private const val FLUSH_DELAY_MS = 2_000L
    private const val MAX_BATCH = 50 // server cap is 100; stay well under
    private const val MAX_QUEUE = 500 // bound memory if the backend is down for a while

    private val queue = ConcurrentLinkedQueue<HistoryReportItem>()
    // Swallow any stray throwable so a background report can never crash the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "HistoryReporter coroutine error (swallowed)") },
    )
    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    /**
     * Cloud sync is opt-out, but the consent dialog now only appears once the user
     * opens the history page ([CloudHistoryConsent] / FragmentHistoryTabs). Until
     * they've actually been asked, nothing leaves the device — otherwise moving the
     * dialog off the home screen would mean uploading before consent was ever shown.
     * Internal so [HistoryBackfill] gates on the exact same condition.
     */
    internal fun cloudSyncAllowed(): Boolean =
        Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    fun enqueue(targetType: String, targetId: Long, payload: JsonElement?) {
        if (!cloudSyncAllowed()) return // opted out, or not yet asked for consent
        if (SessionManager.loggedInUid <= 0L) return // history is per-viewer
        // drop oldest if the backend has been unreachable and the queue piled up
        while (queue.size >= MAX_QUEUE) queue.poll()
        // viewed_at 在入队时定格:flush 有 2s 合并窗口,靠服务端打点会把浏览时间记成上传时间
        queue.add(HistoryReportItem(targetType, targetId, payload, System.currentTimeMillis()))
        if (queue.size >= MAX_BATCH) {
            scope.launch { flush() }
        } else {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /** Drops anything buffered but not yet sent (called when the user turns sync off). */
    fun clearPending() {
        queue.clear()
        flushJob?.cancel()
    }

    /**
     * Fire-and-forget: tell pixshaft-api the current cloud-sync toggle so the admin
     * console's "opted out" list reflects reality. Unlike history upload this MUST
     * run even when sync is off (that's the whole point), so it does NOT gate on
     * [cloudSyncAllowed]. Best-effort: never blocks the UI, swallows all errors.
     */
    fun reportSyncPref(enabled: Boolean) = reportSyncPref(SessionManager.loggedInUid, enabled)

    /** uid-explicit variant for callers that already know it (e.g. the login flow,
     *  where [SessionManager.loggedInUid] may not be wired up yet). */
    fun reportSyncPref(uid: Long, enabled: Boolean) {
        if (uid <= 0L) return // history sync is per-viewer; nothing to report logged out
        scope.launch {
            try {
                Client.pixshaft.reportHistorySyncPref(uid, SyncPrefBody(enabled))
            } catch (ex: Exception) {
                Timber.w(ex, "sync-pref report failed (ignored)")
            }
        }
    }

    /** Drains the queue to the server in MAX_BATCH chunks. Safe to call anytime. */
    suspend fun flush() {
        flushMutex.withLock {
            if (!cloudSyncAllowed()) {
                queue.clear()
                return
            }
            val uid = SessionManager.loggedInUid
            if (uid <= 0L) {
                queue.clear()
                return
            }
            while (true) {
                val batch = ArrayList<HistoryReportItem>(MAX_BATCH)
                while (queue.isNotEmpty() && batch.size < MAX_BATCH) {
                    queue.poll()?.let { batch.add(it) }
                }
                if (batch.isEmpty()) break
                try {
                    Client.pixshaft.reportHistory(uid, HistoryReportBody(batch))
                } catch (ex: Exception) {
                    // Drop this batch; don't spin retrying a flaky network.
                    Timber.e(ex, "history report failed (${batch.size} items dropped)")
                    break
                }
            }
        }
    }
}
