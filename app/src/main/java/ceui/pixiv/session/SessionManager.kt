package ceui.pixiv.session

import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.models.UserModel
import ceui.lisa.utils.Common
import ceui.loxia.AccountResponse
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.UserGender
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivLogin
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

object SessionManager {

    private const val USER_KEY = "LoggedInUserJsonKey"
    const val USE_NEW_UI_KEY = "use-v5-ui"
    const val COOKIE_KEY = "web-api-cookie-v2"
    const val CONTENT_LANGUAGE_KEY = "content-language"

    private val _loggedInAccount = MutableLiveData<AccountResponse>()
    private val gson = Gson()

    val loggedInAccount: LiveData<AccountResponse> = _loggedInAccount

    private val _newTokenEvent = MutableLiveData<Event<Long>>()
    val newTokenEvent: LiveData<Event<Long>> = _newTokenEvent

    fun testRenewAnim() {
        _newTokenEvent.postValue(Event(System.currentTimeMillis()))
    }

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    val isLoggedIn: Boolean
        get() {
            return _loggedInAccount.value?.access_token != null
        }

    /**
     * 网页版会话是否已同步。OAuth 登录只拿到 app-api 的 token，网页 cookie 得用户在
     * 「Web 首页」里登录一次才写进来（见 [ceui.pixiv.ui.web.WebFragment]）。
     * 匿名身份下 www.pixiv.net 的 ajax 只返回全年龄作品，所以依赖网页接口的功能
     * （拉黑、按 tag 筛画师作品…）要先判这一条。
     */
    val hasWebCookie: Boolean
        get() = isLoggedInWebCookie(prefStore.getString(COOKIE_KEY, ""))

    /**
     * 这份 cookie 是不是**已登录**的网页会话。
     *
     * 判据不能是「含 PHPSESSID」：pixiv 在登录页刚打开、用户还没输账号密码时就会先发一个
     * **匿名** PHPSESSID（纯 32 位 hex）。按含即算数的话，登录 WebView 会在 onPageFinished
     * 的第一帧就判成功并被拆掉，用户根本来不及登录；而存下来的匿名 cookie 又让
     * [hasWebCookie] 恒真，反过来把「去网页登录一次」的引导也一并关掉。
     *
     * 登录态的 PHPSESSID 形如 `<uid>_<hash>`，以数字前缀为准。
     */
    fun isLoggedInWebCookie(cookie: String?): Boolean =
        cookie != null && LOGGED_IN_PHPSESSID.containsMatchIn(cookie)

    private val LOGGED_IN_PHPSESSID = Regex("""PHPSESSID=\d+_""")
    private val LOGGED_IN_SESSION_VALUE = Regex("""\d+_.+""")

    /**
     * 按名字去重 [CookieManager.getCookie] 拼出来的 cookie 串。
     *
     * 它会把 `.pixiv.net` 和 `www.pixiv.net` 两个域下的同名 cookie 一并吐出来，登录前后
     * 各写过一次 PHPSESSID 时就并排出现两条：
     * `…; PHPSESSID=<匿名hash>; …; PHPSESSID=<uid>_<hash>; …`。
     * 服务端只认先出现的那条，于是「明明带着登录 cookie，却仍被当匿名」——画师按 tag 筛选
     * 恒 0 件就是这么来的。
     *
     * 同名取最后一条（新值覆盖旧值）；PHPSESSID 额外保护：已经收下登录态的那条后，
     * 不再被后面的匿名值盖回去。
     */
    fun normalizeWebCookie(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val byName = LinkedHashMap<String, String>()
        for (part in raw.split(';')) {
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (name == "PHPSESSID" &&
                byName[name]?.let { LOGGED_IN_SESSION_VALUE.matches(it) } == true &&
                !LOGGED_IN_SESSION_VALUE.matches(value)
            ) {
                continue
            }
            byName[name] = value
        }
        return byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    val loggedInUid: Long
        get() {
            return _loggedInAccount.value?.user?.id ?: 0L
        }

    val loggedInUser: User?
        get() {
            return _loggedInAccount.value?.user
        }

    val isPremium: Boolean
        get() {
            return _loggedInAccount.value?.user?.is_premium == true
        }

    val mailAddress: String?
        get() {
            return _loggedInAccount.value?.user?.mail_address
        }

    val accountName: String?
        get() {
            return _loggedInAccount.value?.user?.account
        }

    val isMailAuthorized: Boolean
        get() {
            return _loggedInAccount.value?.user?.is_mail_authorized == true
        }

    val refreshToken: String?
        get() {
            return _loggedInAccount.value?.refresh_token
        }

    fun initialize() {
        val json = prefStore.getString(USER_KEY, "")
        if (json?.isNotEmpty() == true) {
            try {
                val accountResponse = gson.fromJson(json, AccountResponse::class.java)
                _loggedInAccount.value = accountResponse
                accountResponse.user?.let {
                    ObjectPool.update(it)
                }
                return
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        // Migration: if MMKV has no data, try loading from legacy SharedPreferences
        migrateFromLegacyIfNeeded()
    }

    /**
     * Migrate user data from old SharedPreferences (Local.getUser()) to MMKV.
     * Only migrates when SessionManager has no data AND SharedPreferences has data.
     */
    private fun migrateFromLegacyIfNeeded() {
        try {
            val legacyJson = Shaft.sPreferences?.getString("user", "") ?: return
            if (legacyJson.isEmpty()) return

            val userModel = gson.fromJson(legacyJson, UserModel::class.java) ?: return
            Timber.d("Migrating user data from SharedPreferences to SessionManager (MMKV)")
            updateSession(userModel)
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to migrate legacy user data")
        }
    }

    fun updateSession(userModel: UserModel?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.value = AccountResponse()
        } else {
            val javaJson = gson.toJson(userModel)
            val accountResponse = gson.fromJson(javaJson, AccountResponse::class.java)
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.value = accountResponse
        }
    }

    fun postUpdateSession(userModel: UserModel?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.postValue(AccountResponse())
        } else {
            val javaJson = gson.toJson(userModel)
            val accountResponse = gson.fromJson(javaJson, AccountResponse::class.java)
            prefStore.putString(USER_KEY, gson.toJson(accountResponse))
            _loggedInAccount.postValue(accountResponse)
        }
    }

    private val profileSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PROFILE_SYNC_COOLDOWN_MS = 10 * 60 * 1000L
    private const val PROFILE_SYNC_FAILURE_COOLDOWN_MS = 60 * 1000L
    @Volatile
    private var lastProfileSyncAt = 0L
    @Volatile
    private var lastProfileSyncFailedAt = 0L
    @Volatile
    private var profileSyncInFlight = false

    /**
     * 把服务端刚拉到的“自己”的资料合并进当前会话并持久化。
     *
     * [uid] 是请求发起时的登录 uid，写入前再和当前会话比对，防止“请求在途时切换了账号”
     * 把上一个账号的资料盖到当前账号头上。必须在主线程调用（内部 setValue 更新 LiveData）。
     */
    fun ingestFreshUser(fresh: User?, uid: Long) {
        if (fresh == null || !fresh.exist()) return
        val current = _loggedInAccount.value ?: return
        val old = current.user ?: return
        if (old.id != uid) return
        val merged = current.copy(user = old.mergedWith(fresh))
        prefStore.putString(USER_KEY, gson.toJson(merged))
        _loggedInAccount.value = merged
    }

    /**
     * 前台静默同步：去抖 + 单飞 + 失败静默。头像/昵称在站外被修改后，
     * 回到前台会自动拉一次自己的 user/detail 并写回会话。
     */
    fun syncLoggedInProfileIfNeeded() {
        val uid = loggedInUid
        if (uid == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastProfileSyncAt < PROFILE_SYNC_COOLDOWN_MS) return
        if (now - lastProfileSyncFailedAt < PROFILE_SYNC_FAILURE_COOLDOWN_MS) return
        if (profileSyncInFlight) return
        profileSyncInFlight = true
        profileSyncScope.launch {
            try {
                val fresh = Client.appApi.getUserProfile(uid).user
                withContext(Dispatchers.Main) {
                    ingestFreshUser(fresh, uid)
                }
                lastProfileSyncAt = System.currentTimeMillis()
            } catch (t: Throwable) {
                Timber.w(t, "sync logged-in profile failed, keep cached data")
                lastProfileSyncFailedAt = System.currentTimeMillis()
            } finally {
                profileSyncInFlight = false
            }
        }
    }

    /** fresh 有值的字段覆盖旧值，缺的字段保留旧值——避免 user/detail 缺 mail_address 等字段时把会话弄丢信息。 */
    private fun User.mergedWith(fresh: User): User = copy(
        account = fresh.account ?: account,
        name = fresh.name ?: name,
        pixiv_id = fresh.pixiv_id ?: pixiv_id,
        profile_image_urls = fresh.profile_image_urls ?: profile_image_urls,
        is_mail_authorized = fresh.is_mail_authorized ?: is_mail_authorized,
        is_premium = fresh.is_premium ?: is_premium,
        mail_address = fresh.mail_address ?: mail_address,
        x_restrict = fresh.x_restrict ?: x_restrict,
        comment = fresh.comment ?: comment,
        gender = if (fresh.gender != UserGender.UNKNOWN) fresh.gender else gender,
    )

    /**
     * Returns "Bearer xxx" format token for API Authorization header.
     * This replaces the old UserModel.getAccess_token() which added "Bearer " prefix.
     */
    fun getBearerToken(): String {
        return "Bearer " + getAccessToken()
    }

    /**
     * Returns "Bearer xxx" or empty string if not logged in.
     */
    fun getBearerTokenOrEmpty(): String {
        return try {
            getBearerToken()
        } catch (e: Exception) {
            ""
        }
    }

    fun refreshAccessToken(tokenForThisRequest: String): String? {
        val freshAccessToken = getAccessToken()
        if (!TextUtils.equals(freshAccessToken, tokenForThisRequest)) {
            return freshAccessToken
        }

        return runBlocking(Dispatchers.IO) {
            try {
                _newTokenEvent.postValue(Event(System.currentTimeMillis()))
                val refreshToken = _loggedInAccount.value?.refresh_token
                    ?: throw RuntimeException("refresh_token not exist")
                val response = PixivLogin.refreshTokenBlocking(refreshToken)
                delay(500L)
                withContext(Dispatchers.Main) {
                    applyTokenRefresh(
                        response.accessToken,
                        response.refreshToken,
                        response.expiresIn
                    )
                }
                response.accessToken
            } catch (ex: InvalidRefreshTokenException) {
                Timber.w(ex, "refresh_token 被吊销，登出")
                postUpdateSession(null)
                Common.showToast(R.string.string_340)
                Common.restart()
                null
            } catch (ex: Exception) {
                Timber.e(ex)
                null
            }
        }
    }

    /**
     * 只更新 tokens，保留既有的 UserModel metadata（mail/R18/is_premium 等）。
     * 用于 token 刷新完成后同步到 LiveData + 磁盘。
     */
    fun applyTokenRefresh(accessToken: String, refreshToken: String, expiresIn: Int) {
        val existing = _loggedInAccount.value ?: AccountResponse()
        val updated = existing.copy(
            access_token = accessToken,
            refresh_token = refreshToken,
            expires_in = expiresIn,
        )
        prefStore.putString(USER_KEY, gson.toJson(updated))
        _loggedInAccount.value = updated
    }

    fun getAccessToken(): String {
        val account = _loggedInAccount.value ?: throw RuntimeException("account not found")
        return account.access_token ?: throw RuntimeException("access_token not exist")
    }
}
