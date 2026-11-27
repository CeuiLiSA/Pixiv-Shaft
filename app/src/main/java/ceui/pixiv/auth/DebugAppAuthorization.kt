package ceui.pixiv.auth

import android.util.Base64
import ceui.lisa.BuildConfig
import ceui.pixiv.safe.auth.TokenStore
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DebugAppGrant(
    @SerializedName("credential") val credential: String,
    @SerializedName("uid") val boundUid: Long?,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("expiresAt") val expiresAt: Long,
    @SerializedName("localExpiresAt") val localExpiresAt: Long,
    @SerializedName("checkedAt") val checkedAt: Long,
    @SerializedName("state") val state: String,
) {
    val id: String get() = credential.substringBefore('.')
    val authorization: String get() = "Bearer $credential"
    val currentState: String
        get() = if (state == "active" && System.currentTimeMillis() >= localExpiresAt) "expired" else state
}

internal class DebugAuthorizationException(val code: String) : Exception(code)

internal object DebugAppAuthorization {
    private val appKey = Regex("ps_dk_[A-Za-z0-9_-]{43}")
    private val activationPattern = Regex("ps_da_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")
    private val gson = Gson()
    private val store by lazy { TokenStore("pixshaft-debug-app-authorization") }
    private val mutation = Mutex()
    private val saved by lazy {
        MutableStateFlow(if (BuildConfig.DEBUG) {
            runCatching { store.loadApplicationAuthorization()?.let { gson.fromJson(it, DebugAppGrant::class.java) } }
                .getOrNull()
        } else null)
    }
    val changes get() = saved.asStateFlow()

    fun active(): DebugAppGrant? =
        if (BuildConfig.DEBUG) saved.value?.takeIf { it.currentState == "active" } else null

    fun forUid(uid: Long): DebugAppGrant? = active()?.takeIf {
        uid > 0L && (it.boundUid == null || it.boundUid == uid)
    }

    suspend fun apply(key: String): Unit = withContext(Dispatchers.IO) {
        mutation.withLock {
            if (!BuildConfig.DEBUG || !appKey.matches(key)) throw DebugAuthorizationException("debug_key_invalid")
            val previous = saved.value
            val deviceId = store.deviceId()
            // Same installation and key recover a lost response even across process death.
            val digest = MessageDigest.getInstance("SHA-256").digest("$deviceId\n$key".toByteArray())
            val attempt = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val response = AuthNetwork.api.activateDebug(attempt, DebugActivationRequest(key, deviceId))
            if (!response.isSuccessful) {
                val error = response.errorBody()?.use { body ->
                    runCatching { JsonParser.parseString(body.string()).asJsonObject["error"]?.asString }.getOrNull()
                }
                throw DebugAuthorizationException(error ?: "unavailable")
            }
            val body = response.body() ?: throw IOException("Missing authorization response")
            val credential = body.activation?.takeIf { activationPattern.matches(it) }
                ?: throw IOException("Invalid authorization response")
            val now = System.currentTimeMillis()
            if (body.boundUid?.let { it <= 0L } == true || body.serverTime <= 0L || body.expiresAt <= body.serverTime) {
                throw IOException("Invalid authorization deadline")
            }
            val grant = DebugAppGrant(credential, body.boundUid, deviceId, body.expiresAt,
                now + (body.expiresAt - body.serverTime), now, "active")
            val currentUid = SessionManager.loggedInUid
            if (grant.boundUid != null && currentUid > 0L && currentUid != grant.boundUid) {
                revoke(grant)
                throw DebugAuthorizationException("uid_forbidden")
            }
            replace(grant)
            if (previous != null && previous.id != grant.id) revoke(previous)
        }
    }

    suspend fun refreshStatus(): Boolean = withContext(Dispatchers.IO) {
        mutation.withLock {
            val snapshot = saved.value ?: return@withLock true
            try {
                val response = AuthNetwork.api.debugStatus(snapshot.authorization)
                response.errorBody()?.close()
                val body = response.body() ?: return@withLock false
                val state = body.state?.takeIf { it in listOf("active", "expired", "revoked") }
                    ?: return@withLock false
                if (body.boundUid != snapshot.boundUid || body.serverTime <= 0L || body.expiresAt <= 0L) return@withLock false
                val now = System.currentTimeMillis()
                val checked = DebugAppGrant(snapshot.credential, snapshot.boundUid, snapshot.deviceId, body.expiresAt,
                    now + (body.expiresAt - body.serverTime), now, state)
                replace(checked)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutation.withLock {
            val previous = saved.value
            replace(null)
            if (previous != null) revoke(previous)
        }
    }

    private fun replace(grant: DebugAppGrant?) {
        val previous = saved.value
        store.saveApplicationAuthorization(grant?.let { gson.toJson(it) })
        saved.value = grant
        if (previous?.id != grant?.id || previous?.currentState != grant?.currentState) {
            AuthSessionManager.clearCurrentSession()
            MediaAuthSessionManager.clearCurrentSession()
        }
    }

    private suspend fun revoke(grant: DebugAppGrant) {
        try {
            AuthNetwork.api.revokeDebug(grant.authorization).errorBody()?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // The installation grant remains subject to its deadline and admin revocation.
        }
    }
}
