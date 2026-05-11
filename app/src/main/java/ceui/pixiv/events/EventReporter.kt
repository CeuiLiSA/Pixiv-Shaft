package ceui.pixiv.events

import android.content.Context
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends bookmark / follow / download events to shaft-api-v2 for community
 * trending aggregation. Three hard rules drive the design:
 *
 *  1) Never block the caller. `report()` is fire-and-forget; everything
 *     happens on a private SupervisorJob + Dispatchers.IO scope.
 *  2) Never throw. Every Throwable is caught and logged. A misbehaving
 *     reporter must not break a bookmark click.
 *  3) Anonymous identity. We send sha256(random UUID) as client_id — never
 *     the user's Pixiv UID, never anything that lets the server correlate
 *     activity to a specific Pixiv account.
 *
 * Logs are verbose by design (per project request). All log lines use the
 * "EventReporter" tag so a single `adb logcat -s EventReporter` shows the
 * full report → enqueue → flush → POST → response chain.
 */
object EventReporter {

    private const val TAG = "EventReporter"

    /** Wire-level event type strings. Must match shaft-api-v2 src/db.js whitelists. */
    object Type {
        const val BOOKMARK = "bookmark"
        const val UNBOOKMARK = "unbookmark"
        const val DOWNLOAD = "download"
        const val FOLLOW = "follow"
        const val UNFOLLOW = "unfollow"
    }

    /** Wire-level target_type strings. Must match shaft-api-v2 src/db.js whitelists. */
    object Target {
        const val ILLUST = "illust"
        const val MANGA = "manga"
        const val NOVEL = "novel"
        const val USER = "user"
    }

    private const val MMKV_KEY_CLIENT_ID = "shaft_events_client_id"
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val FLUSH_THRESHOLD = 10           // POST when queue reaches this many
    private const val MAX_BATCH = 50                 // server caps at 100; stay well under
    private const val MAX_QUEUE = 500                // hard cap, drop oldest above this
    private const val MAX_RETRIES = 3

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "uncaught in scope") }
    )
    private val mutex = Mutex()
    private val queue = ArrayDeque<EventEntry>()

    @Volatile private var clientId: String = ""
    @Volatile private var hmacEnabled: Boolean = false

    private data class EventEntry(
        val type: String,
        val targetType: String,
        val targetId: Long,
        var attempts: Int = 0,
    )

    /** Idempotent. Call from Application.onCreate(). */
    fun init(appCtx: Context) {
        if (!initialized.compareAndSet(false, true)) {
            Timber.tag(TAG).d("init() called again, ignoring")
            return
        }
        try {
            val mmkv = MMKV.defaultMMKV()
            var id = mmkv?.getString(MMKV_KEY_CLIENT_ID, null)
            if (id.isNullOrEmpty()) {
                id = sha256Hex(UUID.randomUUID().toString() + "|" + System.nanoTime())
                mmkv?.putString(MMKV_KEY_CLIENT_ID, id)
                Timber.tag(TAG).i("generated new client_id (head=%s..)", id!!.take(8))
            } else {
                Timber.tag(TAG).i("loaded existing client_id (head=%s..)", id.take(8))
            }
            clientId = id
            hmacEnabled = BuildConfig.SHAFT_EVENTS_HMAC.isNotEmpty()

            Timber.tag(TAG).i(
                "init OK: hmac=%s, baseUrl=%s, flushInterval=%dms, threshold=%d, maxBatch=%d",
                if (hmacEnabled) "enabled" else "disabled (forks/dev)",
                BuildConfig.SHAFT_EVENTS_BASE_URL,
                FLUSH_INTERVAL_MS, FLUSH_THRESHOLD, MAX_BATCH,
            )

            scope.launch {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    flushInternal(reason = "tick")
                }
            }
        } catch (t: Throwable) {
            // Initialization must never crash the app. If MMKV isn't available,
            // we just stay disabled forever for this process.
            Timber.tag(TAG).e(t, "init failed, reporter disabled this session")
            initialized.set(false)
        }
    }

    /**
     * Fire-and-forget. Returns immediately. Drops the event silently when:
     *  - reporter not initialized, or
     *  - this build has no HMAC secret (forks / dev builds), or
     *  - target_id is non-positive.
     *
     * Anonymous and on by default — there is no opt-out toggle. Identity is
     * sha256(random UUID) generated on first launch; Pixiv UID is never sent.
     */
    fun report(type: String, targetType: String, targetId: Long) {
        if (!initialized.get()) {
            Timber.tag(TAG).v("drop %s/%s/%d (not initialized)", type, targetType, targetId)
            return
        }
        if (!hmacEnabled) {
            Timber.tag(TAG).v("drop %s/%s/%d (build has no HMAC secret)", type, targetType, targetId)
            return
        }
        if (targetId <= 0) {
            Timber.tag(TAG).w("drop %s/%s/%d (bad target_id)", type, targetType, targetId)
            return
        }
        scope.launch {
            val triggerFlush = mutex.withLock {
                if (queue.size >= MAX_QUEUE) {
                    queue.removeFirst()
                    Timber.tag(TAG).w("queue overflow (>=%d), dropped oldest", MAX_QUEUE)
                }
                queue.addLast(EventEntry(type, targetType, targetId))
                Timber.tag(TAG).d("enqueue %s/%s/%d (queue=%d)", type, targetType, targetId, queue.size)
                queue.size >= FLUSH_THRESHOLD
            }
            if (triggerFlush) flushInternal(reason = "threshold")
        }
    }

    /** Best-effort flush. Useful from app-lifecycle hooks (e.g. onStop). */
    fun flushNow() {
        if (!initialized.get()) return
        Timber.tag(TAG).d("flushNow() requested")
        scope.launch { flushInternal(reason = "manual") }
    }

    private suspend fun flushInternal(reason: String) {
        val batch = mutex.withLock {
            if (queue.isEmpty()) {
                Timber.tag(TAG).v("flush(reason=%s): queue empty", reason)
                return
            }
            val take = minOf(queue.size, MAX_BATCH)
            val taken = ArrayList<EventEntry>(take)
            repeat(take) { taken.add(queue.removeFirst()) }
            taken
        }
        Timber.tag(TAG).d("flush(reason=%s) batch=%d", reason, batch.size)
        try {
            sendBatch(batch)
        } catch (t: Throwable) {
            // Server-side / network failure. Re-queue at the FRONT (preserving
            // order) and let the next tick try again. Entries that hit
            // MAX_RETRIES are dropped to keep a flapping server from pinning
            // unbounded memory.
            mutex.withLock {
                var requeued = 0
                var dropped = 0
                // Reverse to keep original order when addFirst()-ing.
                for (e in batch.asReversed()) {
                    e.attempts++
                    if (e.attempts > MAX_RETRIES) {
                        dropped++
                    } else {
                        queue.addFirst(e)
                        requeued++
                    }
                }
                Timber.tag(TAG).w(
                    t,
                    "flush failed: requeue=%d drop=%d (queue=%d, maxRetries=%d)",
                    requeued, dropped, queue.size, MAX_RETRIES,
                )
            }
        }
    }

    private suspend fun sendBatch(events: List<EventEntry>) {
        val payload = mapOf(
            "client_id" to clientId,
            "platform" to "android",
            "app_version" to BuildConfig.VERSION_NAME,
            "events" to events.map {
                mapOf(
                    "type" to it.type,
                    "target_type" to it.targetType,
                    "target_id" to it.targetId,
                )
            },
        )
        // Use a local Gson if Shaft.sGson isn't ready yet (tests, very early
        // boot). Identical configuration is fine for this payload.
        val json = (Shaft.sGson ?: Gson()).toJson(payload)
        val sig = hmacSha256Hex(json, BuildConfig.SHAFT_EVENTS_HMAC)
        Timber.tag(TAG).d(
            "POST batch=%d bytes=%d sig=%s..%s",
            events.size, json.length, sig.take(8), sig.takeLast(4),
        )
        val resp = ShaftEventsClient.api.batch(
            sig,
            json.toRequestBody("application/json".toMediaType()),
        )
        Timber.tag(TAG).i(
            "response accepted=%d deduped=%d total=%d (sent=%d, queueAfter=%d)",
            resp.accepted, resp.deduped, resp.total, events.size, queue.size,
        )
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Server treats the secret as ASCII bytes (the hex *string* itself),
     * not as raw bytes decoded from hex — matches Node's
     * `createHmac('sha256', secret)` default behavior.
     */
    private fun hmacSha256Hex(payload: String, secretAscii: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretAscii.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        val hex = "0123456789abcdef"
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4])
            out.append(hex[v and 0x0F])
        }
        return out.toString()
    }
}
