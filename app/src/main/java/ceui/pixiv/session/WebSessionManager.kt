package ceui.pixiv.session

import ceui.pixiv.ui.discovery.WebDiscoverySession
import ceui.pixiv.api.Client
import ceui.pixiv.api.PixivWebApi
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber

/**
 * 官网 Web 会话的持久化状态与最近成功记录管理。
 *
 * 账号管理页（AccountSwitchV3Fragment）与全局 Web 端点联动：
 * 1. 成功记录：当请求带有匹配当前账号的 Web Cookie 并成功响应时，记录当前时间戳并将状态置为 [STATUS_SUCCESS]；
 * 2. 失效联动：当检测或业务捕获到 Web 登录失效（401 / 会话失效）时，立即清空 Web Cookie，并在持久化中记录状态为 [STATUS_FAILED]；
 * 3. 状态隔离：按当前账号 UID 分别隔离存储在 MMKV 中。
 */
object WebSessionManager {

    const val STATUS_NONE = 0
    const val STATUS_SUCCESS = 1
    const val STATUS_FAILED = 2

    private fun lastSuccessKey(uid: Long) = "web_session_last_success_" + uid
    private fun statusKey(uid: Long) = "web_session_status_" + uid
    private fun lastFailKey(uid: Long) = "web_session_last_fail_" + uid
    private fun successCookieKey(uid: Long) = "web_session_success_cookie_" + uid

    private val prefStore: MMKV by lazy { MMKV.defaultMMKV() }

    @JvmStatic
    fun getLastSuccessTime(uid: Long): Long {
        if (uid <= 0L) return 0L
        return prefStore.decodeLong(lastSuccessKey(uid), 0L)
    }

    @JvmStatic
    fun getStatus(uid: Long): Int {
        if (uid <= 0L) return STATUS_NONE
        return prefStore.decodeInt(statusKey(uid), STATUS_NONE)
    }

    /**
     * 记录 Web 端点调用成功态的时间戳，并将状态置为 [STATUS_SUCCESS]。
     */
    @JvmStatic
    fun recordSuccess(uid: Long, cookie: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (!isCurrentSession(uid, cookie)) return false
        prefStore.encode(lastSuccessKey(uid), timestamp)
        // 只保存身份指纹；清除登录时不会在历史成功记录里遗留另一份凭证。
        prefStore.encode(successCookieKey(uid), cookieFingerprint(cookie))
        prefStore.encode(statusKey(uid), STATUS_SUCCESS)
        return true
    }

    /**
     * 记录 Web 端点失效状态，记录失败时间戳，并将状态置为 [STATUS_FAILED]；
     * 同步清空 Web Cookie 与 CSRF Token。
     */
    @JvmStatic
    fun recordFailure(uid: Long, cookie: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (!isCurrentSession(uid, cookie)) return false
        prefStore.encode(lastFailKey(uid), timestamp)
        prefStore.encode(statusKey(uid), STATUS_FAILED)
        prefStore.removeValueForKey(successCookieKey(uid))
        SessionManager.clearWebCookie()
        return true
    }

    /**
     * 近一小时内（3600_000ms）是否有成功的持久化记录。
     */
    @JvmStatic
    fun hasRecentSuccess(uid: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (uid <= 0L) return false
        val status = getStatus(uid)
        if (status != STATUS_SUCCESS) return false
        if (!hasMatchingWebCookie(uid) ||
            prefStore.decodeString(successCookieKey(uid), "") != cookieFingerprint(getCookie())) return false
        val lastSuccess = getLastSuccessTime(uid)
        return (now - lastSuccess) in 0 until 3600_000L
    }

    /**
     * 账号是否有属于本人的有效格式 Web Cookie。
     */
    @JvmStatic
    fun hasMatchingWebCookie(uid: Long): Boolean {
        if (uid <= 0L) return false
        val cookie = getCookie()
        return SessionManager.isLoggedInWebCookie(cookie) && WebDiscoverySession.matchesAccount(cookie, uid)
    }

    fun getCookie(): String =
        SessionManager.normalizeWebCookie(prefStore.decodeString(SessionManager.COOKIE_KEY, ""))

    private fun cookieFingerprint(cookie: String): String =
        SessionManager.normalizeWebCookie(cookie).encodeUtf8().sha256().hex()

    /** 请求结果只属于发出时的那份会话，不能覆盖重新登录或切号后的 Cookie。 */
    fun isCurrentSession(uid: Long, cookie: String): Boolean =
        WebDiscoverySession.matchesAccount(cookie, uid) && getCookie() == SessionManager.normalizeWebCookie(cookie)

    enum class CheckResult { SUCCESS, EXPIRED, UNAVAILABLE, STALE }

    /** 传输失败不等于登录失效；只有官网明确的鉴权错误才清理凭证。 */
    suspend fun check(uid: Long, cookie: String, api: PixivWebApi = Client.webApi): CheckResult {
        if (!isCurrentSession(uid, cookie)) return CheckResult.STALE
        val result = try {
            val response = api.getWebUserDetail(uid, cookie = cookie)
            when {
                SessionManager.isUnauthenticatedWebResponse(response) -> CheckResult.EXPIRED
                response.error == false && response.body != null -> CheckResult.SUCCESS
                else -> CheckResult.UNAVAILABLE
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "Web session check failed")
            if (SessionManager.isUnauthenticatedWebSession(e)) CheckResult.EXPIRED else CheckResult.UNAVAILABLE
        }
        if (!isCurrentSession(uid, cookie)) return CheckResult.STALE
        return when (result) {
            CheckResult.SUCCESS -> if (recordSuccess(uid, cookie)) result else CheckResult.STALE
            CheckResult.EXPIRED -> if (recordFailure(uid, cookie)) result else CheckResult.STALE
            else -> result
        }
    }

    /**
     * 清理账号名下的 Web 会话持久化记录（账号被移除时调用）。
     */
    @JvmStatic
    fun purge(uid: Long) {
        if (uid <= 0L) return
        prefStore.removeValueForKey(lastSuccessKey(uid))
        prefStore.removeValueForKey(statusKey(uid))
        prefStore.removeValueForKey(lastFailKey(uid))
        prefStore.removeValueForKey(successCookieKey(uid))
    }

    /**
     * 主动清除账号名下的 Web 登录态：清空 Web Cookie，并清理持久化记录。
     */
    @JvmStatic
    fun clear(uid: Long) {
        if (uid <= 0L) return
        if (hasMatchingWebCookie(uid)) SessionManager.clearWebCookie()
        purge(uid)
    }
}
