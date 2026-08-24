package ceui.pixiv.events

import android.content.Context
import androidx.lifecycle.asFlow
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.ShaftHmac
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.User
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    /** 上一次成功 POST 到 server 的 (uid, clientId) tuple,值是 "uid:clientId"
     *  连接串。值匹配当前则跳过 POST(server 端 ON CONFLICT 幂等兜底,这只是
     *  省一次网络往返 + 一次 HMAC 计算)。uid 或 clientId 变化都会 cache miss。*/
    private const val MMKV_KEY_LAST_UID_BINDING = "shaft_events_uid_binding_last"
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val FLUSH_THRESHOLD = 10           // POST when queue reaches this many
    private const val MAX_BATCH = 50                 // server caps at 100; stay well under
    private const val MAX_QUEUE = 500                // hard cap, drop oldest above this
    private const val MAX_RETRIES = 3
    // Per-event payload cap — must stay <= server's EVENTS_MAX_PAYLOAD_BYTES
    // (256 KiB). Anything bigger gets the payload stripped (event still
    // reported without it) so a freak large Illust can't break the batch.
    private const val MAX_PAYLOAD_BYTES = 200_000

    private val initialized = AtomicBoolean(false)
    /** True while a POST is in flight. Prevents threshold-triggered flushes from
     *  piling parallel sockets on top of a tick flush — important when the
     *  server is dead/slow and every POST sits on the 15s read timeout. */
    private val flushing = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "uncaught in scope") }
    )
    private val mutex = Mutex()
    private val queue = ArrayDeque<EventEntry>()
    /** Serializes binding POSTs — observer 可能在快速登录/登出场景下短时间内
     *  连发几次,锁住保证同一时刻只有一个 POST 在路上,避免无意义并发。 */
    private val uidBindingMutex = Mutex()

    @Volatile private var clientId: String = ""
    @Volatile private var hmacEnabled: Boolean = false

    /** Read-only access for the "操作记录" page (or anything else that needs to query
     *  the server about THIS client's own events). Empty string before init() finishes. */
    fun currentClientId(): String = clientId

    private data class EventEntry(
        val type: String,
        val targetType: String,
        val targetId: Long,
        /** Pre-serialized JSON of the Illust / Novel / User.
         *  Stored as String (not Any) so a re-queue after network failure
         *  doesn't pay the Gson cost again. null = no payload (server falls
         *  back to whatever it already has in illust_meta / user_meta). */
        val payloadJson: String? = null,
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
            hmacEnabled = ShaftHmac.isConfigured

            Timber.tag(TAG).i(
                "init OK: hmac=%s, baseUrl=%s, flushInterval=%dms, threshold=%d, maxBatch=%d",
                if (hmacEnabled) "enabled" else "disabled (forks/dev)",
                BuildConfig.SHAFT_EVENTS_BASE_URL,
                FLUSH_INTERVAL_MS, FLUSH_THRESHOLD, MAX_BATCH,
            )

            scope.launch {
                while (isActive) {
                    try {
                        delay(FLUSH_INTERVAL_MS)
                        flushInternal(reason = "tick")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        // Belt-and-suspenders: flushInternal already catches its own
                        // network errors. This guards the loop itself so an unexpected
                        // throw (mutex, logging, OOM in a formatter) can't kill the
                        // periodic flush for the rest of the process.
                        Timber.tag(TAG).e(t, "tick loop iteration crashed; continuing")
                    }
                }
            }

            // ── uid ↔ client_id binding observer ───────────────────────────
            // SessionManager.loggedInAccount 一变 → uid > 0 时静默 POST
            // /uid-bindings 把 (this device 的 anonymous client_id, 用户 pixiv uid)
            // 落到 server。distinctUntilChanged 防止 token refresh 这类同 uid
            // re-emit 触发重复 POST;filter > 0 跳过未登录态。submitUidBinding
            // 内部还有 MMKV cache 二次过滤,observer 只是"事件触发点"。
            scope.launch {
                Timber.tag(TAG).i("uid_binding observer launching (will watch SessionManager.loggedInAccount)")
                SessionManager.loggedInAccount.asFlow()
                    .map { it?.user?.id ?: 0L }
                    .distinctUntilChanged()
                    .filter { it > 0L }
                    .collect { uid ->
                        Timber.tag(TAG).i("uid_binding observer fired uid=%d → submitUidBinding()", uid)
                        submitUidBinding(uid)
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
    @JvmOverloads
    fun report(type: String, targetType: String, targetId: Long, payload: Any? = null) {
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
            val payloadJson = serializePayload(payload, targetType, targetId)
            enqueue(EventEntry(type, targetType, targetId, payloadJson))
        }
    }

    /**
     * Java-friendly entry point for USER-targeted follow / unfollow events.
     *
     * The modern Kotlin call sites in UActivity.kt resolve a [User] payload
     * inline (ObjectPool, else a single getUserProfile fetch) and hand it to
     * [report]. Legacy RxJava call sites in PixivOperate.java can't await a
     * suspend lookup, so this helper runs the same resolve on EventReporter's
     * own scope before enqueueing. Best-effort: pool miss + network failure
     * falls back to a null payload, matching the existing behavior for
     * unattachable events.
     */
    fun reportFollowUser(userId: Long, isFollow: Boolean) {
        val type = if (isFollow) Type.FOLLOW else Type.UNFOLLOW
        if (!initialized.get()) {
            Timber.tag(TAG).v("drop %s/%s/%d (not initialized)", type, Target.USER, userId)
            return
        }
        if (!hmacEnabled) {
            Timber.tag(TAG).v("drop %s/%s/%d (build has no HMAC secret)", type, Target.USER, userId)
            return
        }
        if (userId <= 0) {
            Timber.tag(TAG).w("drop %s/%s/%d (bad target_id)", type, Target.USER, userId)
            return
        }
        scope.launch {
            // ObjectPool's backing store is a plain HashMap mutated only on
            // the main thread (UActivity.kt et al go through lifecycleScope,
            // which defaults to Dispatchers.Main). Reading it from IO would
            // race with those writes and could throw CME — switch to Main
            // for the lookup, then drop back to IO for the network fallback
            // and serialize / enqueue work.
            val cached = withContext(Dispatchers.Main) {
                runCatching { ObjectPool.get<User>(userId).value }.getOrNull()
            }
            val payload = cached
                ?: runCatching { Client.appApi.getUserProfile(userId).user }.getOrNull()
            val payloadJson = serializePayload(payload, Target.USER, userId)
            enqueue(EventEntry(type, Target.USER, userId, payloadJson))
        }
    }

    private suspend fun enqueue(entry: EventEntry) {
        val triggerFlush = mutex.withLock {
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                Timber.tag(TAG).w("queue overflow (>=%d), dropped oldest", MAX_QUEUE)
            }
            queue.addLast(entry)
            Timber.tag(TAG).d(
                "enqueue %s/%s/%d (queue=%d, payload=%s)",
                entry.type, entry.targetType, entry.targetId, queue.size,
                entry.payloadJson?.let { "${it.length}B" } ?: "—",
            )
            queue.size >= FLUSH_THRESHOLD
        }
        if (triggerFlush) flushInternal(reason = "threshold")
    }

    private fun serializePayload(payload: Any?, targetType: String, targetId: Long): String? {
        if (payload == null) return null
        return try {
            val s = (Shaft.sGson ?: Gson()).toJson(payload)
            clampPayloadJson(s, targetType, targetId)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "payload serialize failed for %s/%d, dropping payload", targetType, targetId)
            null
        }
    }

    private fun clampPayloadJson(json: String?, targetType: String, targetId: Long): String? {
        if (json == null) return null
        if (json.length > MAX_PAYLOAD_BYTES) {
            Timber.tag(TAG).w(
                "payload too large (%dB > %dB) for %s/%d, dropping payload only",
                json.length, MAX_PAYLOAD_BYTES, targetType, targetId,
            )
            return null
        }
        return json
    }

    /** Best-effort flush. Useful from app-lifecycle hooks (e.g. onStop). */
    fun flushNow() {
        if (!initialized.get()) return
        Timber.tag(TAG).d("flushNow() requested")
        scope.launch { flushInternal(reason = "manual") }
    }

    private suspend fun flushInternal(reason: String) {
        // Only one in-flight POST at a time. The tick will retry the queue on
        // its next pass, so a skipped flush is never lost — and during an
        // outage we don't pile up parallel sockets all blocked on the same
        // dead server.
        if (!flushing.compareAndSet(false, true)) {
            Timber.tag(TAG).v("flush(reason=%s): another flush in flight, skipping", reason)
            return
        }
        try {
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
            } catch (ce: CancellationException) {
                // Scope cancellation: put the batch back so a future process
                // restart could re-send (currently in-memory only, but the
                // contract is "no silent loss on cancellation").
                requeueOnFailure(batch, ce)
                throw ce
            } catch (t: Throwable) {
                // Server-side / network failure. Re-queue at the FRONT (preserving
                // order) and let the next tick try again. Entries that hit
                // MAX_RETRIES are dropped to keep a flapping server from pinning
                // unbounded memory.
                requeueOnFailure(batch, t)
            }
        } finally {
            flushing.set(false)
        }
    }

    private suspend fun requeueOnFailure(batch: List<EventEntry>, cause: Throwable) {
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
                cause,
                "flush failed: requeue=%d drop=%d (queue=%d, maxRetries=%d)",
                requeued, dropped, queue.size, MAX_RETRIES,
            )
        }
    }

    /**
     * 静默 POST `(uid, clientId)` 绑定到 server。每个决策点都打 log,方便
     * `adb logcat -s EventReporter` 一眼定位卡在哪。
     *
     * 跳过条件(按顺序检查):
     *   - reporter 未 init → skip
     *   - HMAC disabled (fork build) → skip(端点强制 HMAC,发了也是 401)
     *   - uid <= 0 → skip(observer 已 filter,这里二次防御)
     *   - clientId 空 → skip(init 失败时可能没赋值)
     *   - MMKV 里上一次成功的 token 跟当前完全一致 → skip
     *
     * 否则在 IO scope launch coroutine 进 mutex 走 POST,成功后写 MMKV cache。
     * 失败不抛、不重试 — 下次 observer 触发(再登录 / 重启 app)会重试。
     */
    fun submitUidBinding(uid: Long) {
        if (!initialized.get()) {
            Timber.tag(TAG).d("uid_binding skip: reporter not initialized (uid=%d)", uid)
            return
        }
        if (!hmacEnabled) {
            Timber.tag(TAG).d("uid_binding skip: hmac disabled (fork build) (uid=%d)", uid)
            return
        }
        if (uid <= 0L) {
            Timber.tag(TAG).d("uid_binding skip: non-positive uid (uid=%d)", uid)
            return
        }
        val cid = clientId
        if (cid.isEmpty()) {
            Timber.tag(TAG).w("uid_binding skip: clientId empty (init failed?) (uid=%d)", uid)
            return
        }

        val token = "$uid:$cid"
        val mmkv = MMKV.defaultMMKV()
        val cached = mmkv?.getString(MMKV_KEY_LAST_UID_BINDING, null)
        if (cached == token) {
            Timber.tag(TAG).i(
                "uid_binding skip: cache hit (uid=%d clientId head=%s..)",
                uid, cid.take(8),
            )
            return
        }
        Timber.tag(TAG).i(
            "uid_binding cache miss: cached=%s current=%d:%s.. — queueing POST",
            cached?.take(20)?.plus("..") ?: "<null>",
            uid, cid.take(8),
        )

        scope.launch {
            uidBindingMutex.withLock {
                // 进锁后再 check 一次:并发触发(罕见)时第二个 caller 看到第一个
                // 已经写过 cache 就直接退,避免重复 POST。
                val cachedNow = MMKV.defaultMMKV()?.getString(MMKV_KEY_LAST_UID_BINDING, null)
                if (cachedNow == token) {
                    Timber.tag(TAG).d("uid_binding skip after lock: another caller wrote cache (uid=%d)", uid)
                    return@withLock
                }
                postUidBindingOnce(uid, cid, token)
            }
        }
    }

    private suspend fun postUidBindingOnce(uid: Long, cid: String, token: String) {
        // 手动拼 JSON,跟 ChatFrameEncoder 同风格 — 两个字段无 metachar 风险,
        // clientId 是 hex64、uid 是 Long,直接字符串内插安全。
        val body = """{"client_id":"$cid","uid":$uid}"""
        val sig = ShaftHmac.signHex(body)
        Timber.tag(TAG).d(
            "uid_binding POST starting uid=%d clientId head=%s.. bodyBytes=%d sig head=%s.. sig tail=%s",
            uid, cid.take(8), body.length, sig.take(8), sig.takeLast(4),
        )
        val t0 = System.currentTimeMillis()
        try {
            val resp = ShaftEventsClient.api.bindUid(
                sig,
                body.toRequestBody("application/json".toMediaType()),
            )
            val elapsed = System.currentTimeMillis() - t0
            if (resp.ok) {
                MMKV.defaultMMKV()?.putString(MMKV_KEY_LAST_UID_BINDING, token)
                Timber.tag(TAG).i(
                    "uid_binding POST ok uid=%d status=%s elapsedMs=%d → cache written",
                    uid, resp.status ?: "-", elapsed,
                )
            } else {
                // server 返回 ok=false 是协议异常(我们写的 endpoint 只有 ok=true
                // 或 4xx/5xx),理论上不该发生 — 不写 cache,下次重试。
                Timber.tag(TAG).w(
                    "uid_binding POST returned ok=false uid=%d status=%s elapsedMs=%d (NOT caching)",
                    uid, resp.status ?: "-", elapsed,
                )
            }
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - t0
            Timber.tag(TAG).w(
                t, "uid_binding POST failed uid=%d elapsedMs=%d (will retry on next observer fire)",
                uid, elapsed,
            )
        }
    }

    private suspend fun sendBatch(events: List<EventEntry>) {
        val gson = Shaft.sGson ?: Gson()
        val eventList = events.map { e ->
            val obj = mutableMapOf<String, Any>(
                "type" to e.type,
                "target_type" to e.targetType,
                "target_id" to e.targetId,
            )
            // Re-parse the pre-serialized payload back into a JsonElement so
            // Gson nests it as an object, not as a quoted JSON string.
            if (e.payloadJson != null) {
                obj["payload"] = JsonParser.parseString(e.payloadJson)
            }
            obj
        }
        // app_version carries a -debug suffix so server-side rankings can tell
        // dev builds from production traffic. channel = build flavor (google /
        // github) for the same purpose, on a separate dimension.
        val versionTag = if (BuildConfig.IS_DEBUG_MODE) {
            BuildConfig.VERSION_NAME + "-debug"
        } else {
            BuildConfig.VERSION_NAME
        }
        val payload = mapOf(
            "client_id" to clientId,
            "platform" to "android",
            "channel" to BuildConfig.UPDATE_CHANNEL,
            "app_version" to versionTag,
            "events" to eventList,
        )
        val json = gson.toJson(payload)
        val sig = ShaftHmac.signHex(json)
        val withPayload = events.count { it.payloadJson != null }
        Timber.tag(TAG).d(
            "POST batch=%d (with-payload=%d) bytes=%d sig=%s..%s",
            events.size, withPayload, json.length, sig.take(8), sig.takeLast(4),
        )
        val resp = ShaftEventsClient.api.batch(
            sig,
            json.toRequestBody("application/json".toMediaType()),
        )
        Timber.tag(TAG).i(
            "response accepted=%d deduped=%d total=%d meta_inserted=%d meta_skipped=%d (queueAfter=%d)",
            resp.accepted, resp.deduped, resp.total,
            resp.meta_inserted ?: 0, resp.meta_skipped ?: 0, queue.size,
        )
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).toHex()
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
