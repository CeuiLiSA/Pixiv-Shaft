package ceui.pixiv.actions

import android.content.Context
import androidx.annotation.Keep
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.BindOnlineReq
import ceui.pixiv.shaftapi.Nana7miInvalidReq
import ceui.pixiv.websocket.AppNetworkMonitor
import com.tencent.mmkv.MMKV
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import timber.log.Timber

/**
 * Process-wide durable outbox for AccountResponse reports and invalid-token quarantine notices.
 *
 * These operations belong to the Pixshaft service, not to the currently logged-in Pixiv account;
 * they therefore must survive logout/account switching and cannot share the owner-partitioned
 * bookmark queue. A refreshed AccountResponse is synchronously committed to app-private MMKV
 * before any network attempt. Same-type/same-uid writes replace older data.
 *
 * One instance per process, constructed by [ceui.lisa.activities.Shaft] and exposed through
 * [ceui.pixiv.services.ServicesProvider.accountOnlineReportOutbox]. The constructor is cheap (no IO, no
 * coroutines); [start] launches the worker.
 */
class AccountOnlineReportOutbox internal constructor(
    /** Resolved lazily so JVM unit tests can build one without an Android Context. */
    private val appContext: Lazy<Context>,
) {

    constructor(app: Context) : this(lazy { app.applicationContext })

    enum class PersistResult { DELIVERED, QUEUED, FAILED }

    // Field names are part of the on-disk JSON schema and must survive release minification.
    @Keep
    private data class PersistedOperation(
        val id: String,
        val type: String,
        val uid: Long,
        val account: AccountResponse? = null,
        val refreshTokenHash: String? = null,
        val attempts: Int = 0,
        val nextAttemptAt: Long = 0L,
        val appVersionCode: Int = BuildConfig.VERSION_CODE,
    )

    private enum class AttemptResult { DELIVERED, DEFERRED, MISSING }

    private data class RetryDecision(val permanent: Boolean, val delayMs: Long = 0L)

    private val started = AtomicBoolean(false)
    private val storageMutex = Mutex()
    private val networkMutex = Mutex()
    // If MMKV cannot persist a retry transition (for example disk-full), the old row still looks
    // immediately due. Park it for this process so the worker cannot spin/network-hammer. A newer
    // same-uid report clears the park; a process restart gives storage one fresh recovery attempt.
    private val processParkedKeys = ConcurrentHashMap.newKeySet<String>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    Timber.tag(TAG).e(error, "account outbox worker crashed")
                },
    )

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    @Volatile
    private var monitor: ceui.pixiv.websocket.NetworkMonitor? = null

    /** Idempotent. Called by Shaft's deferred init; launches the delivery worker. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        monitor = AppNetworkMonitor.get(appContext.value)
        scope.launch {
            workerLoop()
        }
        scope.launch {
            monitor?.observeConnectivity?.collect { online ->
                if (online) wakeups.trySend(Unit)
            }
        }
    }

    /** Best-effort caller API; persistence itself runs off the caller thread. */
    fun enqueueOnline(uid: Long, account: AccountResponse) {
        if (!validAccount(uid, account)) {
            Timber.tag(TAG).e("online report rejected before enqueue uid=%d", uid)
            return
        }
        scope.launch {
            if (!persist(onlineOperation(uid, account), clearInvalidation = true)) {
                Timber.tag(TAG).e("online report persistence failed uid=%d", uid)
            }
        }
    }

    /**
     * Refresh-critical path: persist first, then make one immediate delivery attempt.
     * QUEUED is safe for the search to continue because the newest refresh token is durable.
     */
    suspend fun persistAndAttemptOnline(uid: Long, account: AccountResponse): PersistResult {
        if (!persistOnline(uid, account)) return PersistResult.FAILED
        return attemptOnline(uid)
    }

    /** Commit the refreshed response before entering any cancellable network call. */
    suspend fun persistOnline(uid: Long, account: AccountResponse): Boolean {
        if (!validAccount(uid, account)) return false
        return persist(onlineOperation(uid, account), clearInvalidation = true)
    }

    /** Try the currently persisted response once; failures remain in the outbox. */
    suspend fun attemptOnline(uid: Long): PersistResult =
        when (attempt(operationKey(TYPE_ONLINE, uid))) {
            AttemptResult.DELIVERED -> PersistResult.DELIVERED
            AttemptResult.DEFERRED, AttemptResult.MISSING -> PersistResult.QUEUED
        }

    /** Persist a definitive Pixiv invalid_grant notice without storing/logging the token itself. */
    suspend fun persistInvalidRefreshToken(uid: Long, refreshToken: String): Boolean {
        if (uid <= 0L || refreshToken.isBlank()) return false
        val operation = PersistedOperation(
            id = UUID.randomUUID().toString(),
            type = TYPE_INVALID,
            uid = uid,
            refreshTokenHash = sha256(refreshToken),
        )
        return persist(operation, clearInvalidation = false)
    }

    private fun onlineOperation(uid: Long, account: AccountResponse) = PersistedOperation(
        id = UUID.randomUUID().toString(),
        type = TYPE_ONLINE,
        uid = uid,
        account = account,
    )

    private suspend fun persist(
        operation: PersistedOperation,
        clearInvalidation: Boolean,
    ): Boolean = storageMutex.withLock {
        val key = operationKey(operation)
        try {
            val encoded = store.encode(key, Shaft.sGson.toJson(operation))
            if (!encoded) {
                // Do not let an older same-key row continue sending after its replacement failed.
                processParkedKeys.add(key)
                return@withLock false
            }
            processParkedKeys.remove(key)
            if (clearInvalidation) {
                val invalidationKey = operationKey(TYPE_INVALID, operation.uid)
                try {
                    store.removeValueForKey(invalidationKey)
                    processParkedKeys.remove(invalidationKey)
                } catch (error: Exception) {
                    // A stale invalidation is hash-checked by the server, so keeping it is safe and
                    // must not turn a successfully persisted fresh token into a local failure.
                    Timber.tag(TAG).w(
                        error,
                        "account outbox stale invalidation cleanup failed uid=%d",
                        operation.uid,
                    )
                }
            }
            // Refresh rotated the only valid token. Force this write to disk before returning.
            store.sync()
            wakeups.trySend(Unit)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Timber.tag(TAG).e(error, "account outbox persist failed uid=%d type=%s", operation.uid, operation.type)
            false
        }
    }

    private suspend fun workerLoop() {
        try {
            reactivateAfterAppUpdate()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            // A corrupt/unavailable store must not permanently kill the only delivery worker.
            Timber.tag(TAG).e(error, "account outbox startup recovery failed")
        }
        wakeups.trySend(Unit)
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                val network = monitor
                if (network == null || !network.isConnected) {
                    wakeups.receive()
                    continue
                }

                val now = System.currentTimeMillis()
                val operations = readAll()
                val due = operations
                    .filter {
                        it.attempts < MAX_ATTEMPTS &&
                                it.nextAttemptAt <= now &&
                                operationKey(it) !in processParkedKeys
                    }
                    .sortedBy { it.nextAttemptAt }
                if (due.isNotEmpty()) {
                    for ((index, operation) in due.withIndex()) {
                        attempt(operationKey(operation))
                        if (index != due.lastIndex) delay(MIN_GAP_MS)
                    }
                    continue
                }

                val earliest = operations
                    .asSequence()
                    .filter {
                        it.attempts < MAX_ATTEMPTS && operationKey(it) !in processParkedKeys
                    }
                    .map { it.nextAttemptAt }
                    .minOrNull()
                if (earliest == null) {
                    wakeups.receive()
                } else {
                    val waitMs = (earliest - now).coerceIn(1L, MAX_IDLE_WAIT_MS)
                    withTimeoutOrNull(waitMs) { wakeups.receive() }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "account outbox worker pass failed")
                delay(WORKER_FAILURE_DELAY_MS)
            }
        }
    }

    private suspend fun attempt(key: String): AttemptResult = networkMutex.withLock {
        if (key in processParkedKeys) return@withLock AttemptResult.DEFERRED
        val network = monitor
        if (network == null || !network.isConnected) return@withLock AttemptResult.DEFERRED
        val operation = read(key) ?: return@withLock AttemptResult.MISSING
        if (operation.attempts >= MAX_ATTEMPTS || operation.nextAttemptAt > System.currentTimeMillis()) {
            return@withLock AttemptResult.DEFERRED
        }

        try {
            when (operation.type) {
                TYPE_ONLINE -> {
                    val account = operation.account
                        ?: throw PermanentReportException("missing AccountResponse")
                    // 会员年龄在**发送这一刻**取：这个队列可以离线堆着，攒了半小时才发出去时
                    // 「多久以前看到的」也确实是半小时。按 uid 查而不是「拿当前登录账号的」——
                    // 借来的号也走这个队列，[SessionManager.premiumAgeMs] 对它返回 null，
                    // 于是别人的号不会被借号方经自己代理刷出来的会员状态作证。
                    val ack = Client.pixshaft.bindOnline(
                        BindOnlineReq(
                            operation.uid,
                            account,
                            SessionManager.premiumAgeMs(operation.uid),
                        )
                    )
                    if (!ack.ok || ack.uid != operation.uid) {
                        throw PermanentReportException("online report rejected")
                    }
                }

                TYPE_INVALID -> {
                    val hash = operation.refreshTokenHash
                        ?: throw PermanentReportException("missing refresh token hash")
                    val ack = Client.pixshaft.invalidateNana7mi(Nana7miInvalidReq(operation.uid, hash))
                    if (!ack.ok || ack.uid != operation.uid) {
                        throw PermanentReportException("invalidation report rejected")
                    }
                }

                else -> throw PermanentReportException("unknown operation type")
            }
            removeIfCurrent(key, operation.id)
            Timber.tag(TAG).d("account outbox delivered uid=%d type=%s", operation.uid, operation.type)
            AttemptResult.DELIVERED
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            val decision = retryDecision(error, operation.attempts)
            recordFailureIfCurrent(key, operation, decision)
            Timber.tag(TAG).w(
                error,
                "account outbox deferred uid=%d type=%s permanent=%s attempt=%d",
                operation.uid,
                operation.type,
                decision.permanent,
                operation.attempts + 1,
            )
            AttemptResult.DEFERRED
        }
    }

    private suspend fun recordFailureIfCurrent(
        key: String,
        operation: PersistedOperation,
        decision: RetryDecision,
    ) = storageMutex.withLock {
        try {
            val current = readUnsafe(key) ?: return@withLock
            if (current.id != operation.id) return@withLock
            val attempts = if (decision.permanent) MAX_ATTEMPTS else operation.attempts + 1
            val updated = operation.copy(
                attempts = attempts.coerceAtMost(MAX_ATTEMPTS),
                nextAttemptAt = if (decision.permanent) Long.MAX_VALUE
                else System.currentTimeMillis() + decision.delayMs,
            )
            if (!store.encode(key, Shaft.sGson.toJson(updated))) {
                processParkedKeys.add(key)
                Timber.tag(TAG).e(
                    "account outbox retry state persistence rejected uid=%d type=%s",
                    operation.uid,
                    operation.type,
                )
                return@withLock
            }
            store.sync()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            processParkedKeys.add(key)
            Timber.tag(TAG).e(
                error,
                "account outbox retry state persistence failed uid=%d type=%s",
                operation.uid,
                operation.type,
            )
        }
    }

    private suspend fun removeIfCurrent(key: String, id: String) = storageMutex.withLock {
        val current = readUnsafe(key) ?: return@withLock
        if (current.id == id) {
            store.removeValueForKey(key)
            store.sync()
        }
    }

    private suspend fun reactivateAfterAppUpdate() = storageMutex.withLock {
        for (operation in readAllUnsafe()) {
            if (operation.attempts >= MAX_ATTEMPTS && operation.appVersionCode < BuildConfig.VERSION_CODE) {
                val reactivated = operation.copy(
                    attempts = 0,
                    nextAttemptAt = 0L,
                    appVersionCode = BuildConfig.VERSION_CODE,
                )
                store.encode(operationKey(operation), Shaft.sGson.toJson(reactivated))
            }
        }
        store.sync()
    }

    private suspend fun read(key: String): PersistedOperation? =
        storageMutex.withLock { readUnsafe(key) }

    private suspend fun readAll(): List<PersistedOperation> =
        storageMutex.withLock { readAllUnsafe() }

    private fun readAllUnsafe(): List<PersistedOperation> =
        store.allKeys().orEmpty()
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull(::readUnsafe)
            .toList()

    private fun readUnsafe(key: String): PersistedOperation? {
        val json = try {
            store.decodeString(key) ?: return null
        } catch (error: Exception) {
            // A storage read failure is not proof that the payload is corrupt. Preserve the row
            // and stop touching it in this process; a newer report or restart can recover it.
            processParkedKeys.add(key)
            Timber.tag(TAG).e(error, "account outbox read failed key=%s", key)
            return null
        }
        return try {
            Shaft.sGson.fromJson(json, PersistedOperation::class.java)
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "dropping corrupt account outbox row key=%s", key)
            runCatching { store.removeValueForKey(key) }
            processParkedKeys.remove(key)
            null
        }
    }

    private fun retryDecision(error: Throwable, priorAttempts: Int): RetryDecision {
        if (error is PermanentReportException) return RetryDecision(permanent = true)
        val http = generateSequence(error) { it.cause }.filterIsInstance<HttpException>().firstOrNull()
        if (http != null && http.code() !in TRANSIENT_HTTP_CODES && http.code() !in 500..599) {
            return RetryDecision(permanent = true)
        }
        if (http == null && generateSequence(error) { it.cause }.none { it is IOException }) {
            return RetryDecision(permanent = true)
        }
        val shift = priorAttempts.coerceIn(0, 5)
        val delayMs = (INITIAL_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
        return RetryDecision(permanent = false, delayMs = delayMs)
    }

    private fun validAccount(uid: Long, account: AccountResponse): Boolean =
        uid > 0L &&
                account.user?.id == uid &&
                !account.access_token.isNullOrBlank() &&
                !account.refresh_token.isNullOrBlank()

    private fun operationKey(operation: PersistedOperation): String =
        operationKey(operation.type, operation.uid)

    private fun operationKey(type: String, uid: Long): String = "$KEY_PREFIX$type:$uid"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class PermanentReportException(message: String) : IOException(message)

    private companion object {
        const val TAG = "AccountOnlineOutbox"
        const val MMKV_ID = "account-online-outbox-v1"
        const val KEY_PREFIX = "operation:"
        const val TYPE_ONLINE = "online"
        const val TYPE_INVALID = "invalid_refresh"
        const val MAX_ATTEMPTS = 5
        const val MIN_GAP_MS = 1_000L
        const val INITIAL_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30L * 60_000L
        const val MAX_IDLE_WAIT_MS = 60L * 60_000L
        const val WORKER_FAILURE_DELAY_MS = 60_000L
        val TRANSIENT_HTTP_CODES = setOf(408, 425, 429)
    }
}
