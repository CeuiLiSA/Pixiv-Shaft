package ceui.pixiv.session

import android.os.SystemClock
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Common
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.api.Client
import ceui.pixiv.cache.ObjectPool
import ceui.loxia.User
import ceui.lisa.repo.freshMembershipOf
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivLogin
import ceui.pixiv.login.PixivOAuthResponse
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

object SessionManager {

    private const val USER_KEY = "LoggedInUserJsonKey"
    const val COOKIE_KEY = "web-api-cookie-v2"

    private val _loggedInAccount = MutableLiveData<AccountResponse>()
    private val gson = Gson()

    val loggedInAccount: LiveData<AccountResponse> = _loggedInAccount

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

            val userModel = gson.fromJson(legacyJson, AccountResponse::class.java) ?: return
            Timber.d("Migrating user data from SharedPreferences to SessionManager (MMKV)")
            updateSession(userModel)
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to migrate legacy user data")
        }
    }

    fun updateSession(userModel: AccountResponse?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.value = AccountResponse()
        } else {
            prefStore.putString(USER_KEY, gson.toJson(userModel))
            _loggedInAccount.value = userModel
        }
    }

    fun postUpdateSession(userModel: AccountResponse?) {
        if (userModel == null) {
            prefStore.putString(USER_KEY, "")
            _loggedInAccount.postValue(AccountResponse())
        } else {
            prefStore.putString(USER_KEY, gson.toJson(userModel))
            _loggedInAccount.postValue(userModel)
        }
    }

    /** 一次观测：读到的是哪个号，以及那是开机后的第几毫秒。两个值必须同生共死。 */
    private data class PremiumObservation(val uid: Long, val atElapsed: Long)

    /**
     * 上一次**真去 pixiv 读到**当前登录账号会员状态的时刻，以及那是哪个 uid。
     *
     * uid 和时刻合在**一个**引用里，而不是两个 @Volatile 字段：读方在 IO 线程
     * （[premiumAgeMs] 由上报队列在发送那一刻调），写方在主线程，分开存的话
     * 读完 uid 到读时刻之间插进一次**切账号后的观测**，就会把旧号的 uid 配上
     * 新号的时刻，报出一个比任何真实观测都新的年龄 —— 而服务端正是拿「比那次
     * 拒绝更新」当证据把号放回池子的。一起写、一起读，这个缝就不存在。
     *
     * 走开机后的单调钟（同 [lastProfileSyncAt] 的理由）：这个值最终要变成上报里的
     * `premiumAgeMs`，而墙钟被改或被 NTP 拨过之后算出来的时长会是负数甚至几天。
     * `null` = 本进程还没读到过 —— 冷启到第一次前台同步之间就是这样，那期间上报不带年龄，
     * 服务端按「未知」处理（不会因此掉出池子，只是被拒过的号得等下一次真读到才回得来）。
     *
     * 只记登录账号自己的：借来的号也会被上报，但那份会员状态是借号方经**自己配的
     * PxveAPI 代理**刷出来的，不该拿它去替别人的号作证。
     */
    @Volatile
    private var premiumObservation: PremiumObservation? = null

    /** 记下「刚从 pixiv 读到 [uid] 的会员状态」。只有真读到才调用，沿用旧值的路径不调。 */
    private fun markPremiumObserved(uid: Long) {
        if (uid <= 0L) return
        premiumObservation = PremiumObservation(uid, SystemClock.elapsedRealtime())
    }

    /**
     * 距离上一次真读到 [uid] 的会员状态过了多久（毫秒），说不出来给 null。
     *
     * 给 [ceui.pixiv.actions.AccountOnlineReportOutbox] 在**发送那一刻**取值 —— 上报是
     * 可离线堆积的，攒了半小时才发出去时，「多久以前看到的」也确实是半小时。
     */
    fun premiumAgeMs(uid: Long): Long? {
        val observed = premiumObservation ?: return null
        if (uid <= 0L || uid != observed.uid) return null
        return (SystemClock.elapsedRealtime() - observed.atElapsed).coerceAtLeast(0L)
    }

    private val profileSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PROFILE_SYNC_COOLDOWN_MS = 10 * 60 * 1000L
    private const val PROFILE_SYNC_FAILURE_COOLDOWN_MS = 60 * 1000L

    /**
     * 冷却时间戳走开机后的单调时钟，不用墙钟：墙钟被用户改或被 NTP 往回拨时，
     * `now - last` 会变成负数，两条冷却判断恒成立，静默同步会一直被冻到时钟追回来
     * （同类事故见 PixivActionQueue 的冷却钳制）。
     * `null` = 本进程还没同步过，用它和 elapsedRealtime 开机瞬间的 0 区分开——
     * 否则开机 10 分钟内启动 App 会被误判成「刚同步过」。
     */
    @Volatile
    private var lastProfileSyncAt: Long? = null
    @Volatile
    private var lastProfileSyncFailedAt: Long? = null
    private val profileSyncInFlight = AtomicBoolean(false)

    /**
     * 把服务端刚拉到的“自己”的资料合并进当前会话并持久化。
     *
     * [uid] 是请求发起时的登录 uid，写入前再和当前会话比对，防止“请求在途时切换了账号”
     * 把上一个账号的资料盖到当前账号头上。必须在主线程调用（内部 setValue 更新 LiveData）。
     *
     * 合并结果和旧值一致时直接返回：这条路径每次回前台都会走一遍，无变化还落盘 + 发
     * LiveData，会让所有观察者（侧边栏、「我的」）白重绑一次。
     */
    @JvmOverloads
    fun ingestFreshUser(fresh: User?, uid: Long, premium: Boolean? = null) {
        if (fresh == null || !fresh.exist()) return
        val current = _loggedInAccount.value ?: return
        val old = current.user ?: return
        if (old.id != uid) return
        // 读到了就记，哪怕值没变：服务端要的是「什么时候看的」，不是「什么时候变的」。
        // 记在下面那条「无变化就返回」之前，否则一个一直是会员的号永远盖不上这个戳。
        if (premium != null) markPremiumObserved(uid)
        val mergedUser = old.mergedWith(fresh, premium)
        if (mergedUser == old) return
        val merged = current.copy(user = mergedUser)
        prefStore.putString(USER_KEY, gson.toJson(merged))
        _loggedInAccount.value = merged
        if (mergedUser.is_premium != old.is_premium) {
            // 会员状态变了就立刻上报，不等下一次 token 刷新（可能一小时后，也可能一直不来）。
            // 借号池是按上报值派发的：会员过期后还挂在池子里，借号的人会白白花掉一次额度；
            // 刚买了会员却报着没有，自己的号又白白进不了池子。这一条走到才发的，静默同步
            // 无变化时会先在上面 return，不会变成每 10 分钟一次的定时上报。
            PixivActions.bindAccountOnline(uid, merged)
        }
    }

    /**
     * 用一份**绝对权威**的会员状态强制上报一次。
     *
     * [uid] 是 self profile（`/v1/user/me/state`）响应里 `profile.user_id` —— 这份 [premium] 的
     * **真正归属**。[premium] 是同一响应的 `profile.is_premium`，和 user/detail 的权威字段同源，
     * 由冷启动预热 [ceui.pixiv.session.SelfProfileWarmup] 刚从 pixiv 真读到，age≈0。
     *
     * **绝不上报错值的三道闸**（这是本方法存在的全部理由，用户最怕的就是把 A 的会员状态安到 B 上）：
     *  1. `uid <= 0` → 畸形响应（缺 user_id），直接不报；
     *  2. `uid != loggedInUid` → self profile 请求在途时切了号，这份 premium 不属于当前会话，不报。
     *     用响应自带的 user_id 而不是「当前登录 uid」正是为了这一步——后者会把 A 的 premium 报成 B 的；
     *  3. `account.user.id != uid` → 再兜一层，会话账号必须就是这个 uid 才动。
     * 三闸全过之后，上报体直接用 [premium] 覆盖 `is_premium`，不依赖 [ingestFreshUser] 是否真把权威值
     * 写进了会话（它在 `fresh` 不 exist 时会提前 return），保证送出去的**永远**是这份权威值。
     *
     * 和 [ingestFreshUser] 的区别只有一处：那条**只在 is_premium 相对旧值变化时**才上报，服务于
     * 日常静默同步；这里**无条件**报一次 —— 哪怕值没变，也要把一份 `premiumAgeMs≈0` 的新观测送上
     * 去，好让曾被借号方误判过的号凭「更晚的真读」回到借号池（见 [ceui.pixiv.shaftapi.BindOnlineReq.premiumAgeMs]）。
     *
     * [markPremiumObserved] 在校验通过后才调：否则会给一个并不属于当前号的观测打上 age 戳。
     *
     * 幂等：outbox 按 `TYPE_ONLINE:uid` 覆盖去重，即便 [ingestFreshUser] 因值变化也报了一次，
     * 两条会并成同一条，不会重复发。必须在主线程调用（内部经 [ingestFreshUser] 更新 LiveData，
     * 且读 [loggedInUid] 与切号的 setValue 同在主线程串行，不会读到半更新态）。
     */
    fun reportAuthoritativePremium(fresh: User?, uid: Long, premium: Boolean) {
        if (uid <= 0L || uid != loggedInUid) return
        markPremiumObserved(uid)
        ingestFreshUser(fresh, uid, premium)
        val account = _loggedInAccount.value ?: return
        val user = account.user ?: return
        if (user.id != uid) return
        val authoritative = account.copy(user = user.copy(is_premium = premium))
        PixivActions.bindAccountOnline(uid, authoritative)
    }

    /**
     * 前台静默同步：去抖 + 单飞 + 失败静默。头像/昵称在站外被修改后，
     * 回到前台会自动拉一次自己的 user/detail 并写回会话。
     */
    fun syncLoggedInProfileIfNeeded() {
        val uid = loggedInUid
        if (uid == 0L) return
        val now = SystemClock.elapsedRealtime()
        lastProfileSyncAt?.let { if (now - it < PROFILE_SYNC_COOLDOWN_MS) return }
        lastProfileSyncFailedAt?.let { if (now - it < PROFILE_SYNC_FAILURE_COOLDOWN_MS) return }
        if (!profileSyncInFlight.compareAndSet(false, true)) return
        profileSyncScope.launch {
            try {
                val response = Client.appApi.getUserProfile(uid)
                withContext(Dispatchers.Main) {
                    ingestFreshUser(response.user, uid, response.profile?.is_premium)
                }
                lastProfileSyncAt = SystemClock.elapsedRealtime()
            } catch (ex: CancellationException) {
                throw ex
            } catch (t: Throwable) {
                Timber.w(t, "sync logged-in profile failed, keep cached data")
                lastProfileSyncFailedAt = SystemClock.elapsedRealtime()
            } finally {
                profileSyncInFlight.set(false)
            }
        }
    }

    /**
     * fresh 有值的字段覆盖旧值，缺的字段保留旧值——避免 user/detail 缺 mail_address 等字段时把会话弄丢信息。
     *
     * 只合并可空字段：它们缺省就是 null，能把「服务端没返回」和「服务端返回了空」区分开。
     * 非空且带默认值的字段（如 `gender` 默认 MALE）做不到这个区分，合进来等于拿默认值
     * 覆盖真实值，所以一律不动。
     */
    private fun User.mergedWith(fresh: User, premium: Boolean?): User = copy(
        account = fresh.account ?: account,
        name = fresh.name ?: name,
        pixiv_id = fresh.pixiv_id ?: pixiv_id,
        profile_image_urls = fresh.profile_image_urls ?: profile_image_urls,
        is_mail_authorized = fresh.is_mail_authorized ?: is_mail_authorized,
        // 会员状态**只**认调用方显式传进来的那份，不从 [fresh] 里捡。
        //
        // 权威字段是 user/detail 同一份响应里的 `profile.is_premium`（[UserResponse.isPremium]
        // 读的就是它），而 [fresh] 是那份响应的 `user` 对象。该字段在精简响应里可能缺失，
        // 因此不能拿缺省值覆盖已有会员状态，否则会上报成非会员并让账号掉出借号池。
        //
        // 于是这里的规则很硬：会员状态只在调用方说「我这次真读到了」时才变。前台静默同步
        // 和「我的」页都传 profile.is_premium，其余调用方一概动不了它。
        is_premium = premium ?: is_premium,
        mail_address = fresh.mail_address ?: mail_address,
        x_restrict = fresh.x_restrict ?: x_restrict,
        comment = fresh.comment ?: comment,
    )

    /**
     * Returns "Bearer xxx" format token for API Authorization header.
     * AccountResponse stores the raw token; HTTP callers use this helper for the Bearer form.
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

    /**
     * 见 [SingleFlightTokenRefresher]。锁内会 `withContext(Main)` 回主线程写 LiveData；
     * 主线程永远不会调 [refreshAccessToken]，不存在反向拿锁，不会死锁。
     *
     * 已登出（例如并发线程刚因吊销把会话清掉）时 [getAccessToken] 会 throw，
     * 这里换成 null，别把异常抛进 OkHttp 线程。
     */
    private val tokenRefresher = SingleFlightTokenRefresher(
        currentToken = { runCatching { getAccessToken() }.getOrNull() },
        doRefresh = ::doRefreshLocked,
    )

    /**
     * 由两套 OkHttp 栈的拦截器（[ceui.pixiv.network.TokenFetcherInterceptor] / Retro 的
     * [ceui.lisa.http.TokenInterceptor]）在 OkHttp 线程上调用：拿着「本次请求用的
     * access token」来换一个新的。两栈共用这一把锁，同一个旧 token 只会触发一次真正的刷新请求。
     *
     * @return 新 access token；拿不到（网络失败 / 已登出）返回 null，调用方原样返回 400。
     */
    fun refreshAccessToken(tokenForThisRequest: String): String? =
        tokenRefresher.refresh(tokenForThisRequest)

    private fun doRefreshLocked(): String? {
        return runBlocking(Dispatchers.IO) {
            try {
                val refreshToken = _loggedInAccount.value?.refresh_token
                    ?: throw RuntimeException("refresh_token not exist")
                val response = PixivLogin.refreshTokenBlocking(refreshToken)
                withContext(Dispatchers.Main) {
                    applyTokenRefresh(
                        response.accessToken,
                        response.refreshToken,
                        response.expiresIn,
                        freshPremiumOf(response),
                    )
                }
                response.accessToken
            } catch (ex: InvalidRefreshTokenException) {
                Timber.tag("TokenRefresh").e(ex, "refresh rejected as INVALID refresh token → signing out + restart")
                postUpdateSession(null)
                Common.showToast(R.string.string_340)
                Common.restart()
                null
            } catch (ex: Exception) {
                Timber.tag("TokenRefresh").e(ex, "refresh failed (transient), keeping session")
                null
            }
        }
    }

    /**
     * 更新 tokens 并采纳本次 OAuth 响应说的会员状态，其余 metadata（mail/R18…）原样保留。
     * 用于 token 刷新完成后同步到 LiveData + 磁盘。
     *
     * [freshPremium] 为 null = pixiv 这次没提会员（部分刷新响应就是不带这个字段），
     * 保留旧值；绝不能把「没说」写成「不是会员」，那会把一个付费号踢出借号池。
     * 同一条判断见 [ceui.lisa.repo.mergeMembership]。
     */
    @JvmOverloads
    fun applyTokenRefresh(
        accessToken: String,
        refreshToken: String,
        expiresIn: Int,
        freshPremium: Boolean? = null,
    ) {
        val existing = _loggedInAccount.value ?: AccountResponse()
        val updated = existing.copy(
            access_token = accessToken,
            refresh_token = refreshToken,
            expires_in = expiresIn,
            user = if (freshPremium == null) existing.user
            else existing.user?.copy(is_premium = freshPremium),
        )
        if (freshPremium != null) markPremiumObserved(existing.user?.id ?: 0L)
        PixivActions.bindAccountOnline(existing.user?.id ?: 0L, updated)
        prefStore.putString(USER_KEY, gson.toJson(updated))
        _loggedInAccount.value = updated
    }

    /**
     * 这次刷新响应能替**当前登录账号**说的会员状态，说不了就是 null。
     *
     * 判据复用借号那支的 [freshMembershipOf]（uid 对不上就不算数），两处是同一条规则：
     * 一次 token 刷新只能替它自己指名的那个账号说话。
     *
     * 这里多一道 `uid > 0`：[freshMembershipOf] 的调用契约是「uid 已保证 > 0」，而
     * [loggedInUid] 在未登录/会话还没加载完时就是 0，而 pixiv-login 在 pixiv 漏发 id 时
     * 也填 0 —— 两个 0 会相等，等于拿一份认不出主人的响应去改会员状态。
     */
    fun freshPremiumOf(response: PixivOAuthResponse): Boolean? {
        val uid = loggedInUid
        return if (uid <= 0L) null else freshMembershipOf(response.user, uid)
    }

    fun getAccessToken(): String {
        val account = _loggedInAccount.value ?: throw RuntimeException("account not found")
        return account.access_token ?: throw RuntimeException("access_token not exist")
    }
}

/**
 * 上报该带哪个会员状态：pixiv 这次亲口说的 > 会话里那份 > 调用方手上的旧值。
 *
 * 顺序就是「谁的信息更新」的顺序，而不是「谁更方便拿到」。[stored] 排最后是因为它来自
 * 登录时冻下来的那份 SharedPreferences 副本 —— 全 app 唯一一处从不更新的会员状态，
 * 而借号池正是按上报值决定派不派发的。
 */
internal fun resolvePremiumForReport(
    fresh: Boolean?,
    session: Boolean?,
    stored: Boolean,
): Boolean = fresh ?: session ?: stored
