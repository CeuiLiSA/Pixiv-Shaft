package ceui.pixiv.config

import android.os.SystemClock
import ceui.loxia.AppConfigResponse
import ceui.loxia.Client
import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动向 pixshaft-api 拉一次的基础配置（`GET /v1/config`），目前只有「借号搜索总开关」。
 *
 * 存在的意义是让服务端能在不发版的前提下关掉一个客户端功能，所以设计上只守两条：
 *
 * 1. **调用方永远不等网络。** 值先从 MMKV 读上一次的结果（首帧就有确定答案），拉取在后台
 *    跑完再覆盖。读接口是纯内存字段。
 * 2. **只有服务端明确说话才改。** 超时、5xx、字段缺失都保留上一次已知值（首次安装即默认
 *    值），也就是 fail-open —— 拉不到配置绝不能顺手把功能关了。
 *
 * uid 只是灰度分桶键：服务端按它决定白/黑名单。因此登录态变化后要重拉一次，否则「只给某个
 * uid 开」要等到下次冷启动才生效；这件事由 [nana7miSearchEnabled] 的读取顺带触发，不用在
 * 登录流程里另挂钩子。
 */
object RemoteAppConfig {

    private val initialized = AtomicBoolean(false)
    private val fetching = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    Timber.tag(TAG).e(error, "remote config worker crashed")
                },
    )

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    @Volatile
    private var nana7miSearch = DEFAULT_NANA7MI_SEARCH

    /** 最近一次成功拉取所用的 uid（0 = 未登录）；和当前登录态不一致就说明该重拉了。 */
    @Volatile
    private var fetchedForUid: Long? = null

    /** 最近一次失败的 uid 和时刻（[SystemClock.elapsedRealtime]，改系统时间不受影响）。 */
    @Volatile
    private var failedForUid: Long? = null

    @Volatile
    private var lastFailureAt: Long = 0L

    /**
     * 非会员借号走官方人气排序的总开关。关掉时搜索回到借号功能上线前的行为
     * （直接 popular-preview），不是把人气排序整个变成错误。
     *
     * 读它可能顺带在后台补一次拉取（见 [refreshIfStale]），但绝不阻塞调用方。
     */
    val nana7miSearchEnabled: Boolean
        get() {
            refreshIfStale()
            return nana7miSearch
        }

    /** 冷启动调用一次。必须在 MMKV.initialize 和 SessionManager.initialize 之后。 */
    @JvmStatic
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        nana7miSearch = runCatching {
            store.getBoolean(KEY_NANA7MI_SEARCH, DEFAULT_NANA7MI_SEARCH)
        }.getOrDefault(DEFAULT_NANA7MI_SEARCH)
        Timber.tag(TAG).d("loaded cached config nana7mi_search_enabled=%s", nana7miSearch)
        refreshIfStale()
    }

    /**
     * 登录态变了（或还没成功拉过）就在后台补一次。同一时刻只允许一个请求在飞。
     *
     * 失败后对**同一个 uid** 静默 [RETRY_COOLDOWN_MS]：这个方法挂在配置读取上，而配置是每次
     * 搜索都要读的——没有冷却的话，服务端挂掉或这条路由还没上线（404）就会变成「每搜一次发
     * 一次必然失败的请求」。冷却只按 uid 记，所以刚登录/切号要重拉时不会被上一个 uid 的失败
     * 挡住。
     */
    private fun refreshIfStale() {
        if (!initialized.get()) return
        val uid = SessionManager.loggedInUid
        if (fetchedForUid == uid) return
        if (uid == failedForUid &&
            SystemClock.elapsedRealtime() - lastFailureAt < RETRY_COOLDOWN_MS
        ) {
            return
        }
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            try {
                apply(uid, Client.pixshaft.appConfig(uid.takeIf { it > 0L }))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 拿不到就继续用上一次的值：这是个 kill switch，网络抖动不该改变行为。
                failedForUid = uid
                lastFailureAt = SystemClock.elapsedRealtime()
                Timber.tag(TAG).w(e, "fetch failed, keeping cached config uid=%d", uid)
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun apply(uid: Long, response: AppConfigResponse) {
        fetchedForUid = uid
        failedForUid = null
        val enabled = response.nana7miSearchEnabled
        if (enabled == null) {
            Timber.tag(TAG).d("server has no opinion on nana7mi search, keeping %s", nana7miSearch)
            return
        }
        nana7miSearch = enabled
        runCatching { store.putBoolean(KEY_NANA7MI_SEARCH, enabled) }
        Timber.tag(TAG).i("config applied uid=%d nana7mi_search_enabled=%s", uid, enabled)
    }

    private const val TAG = "RemoteAppConfig"
    private const val MMKV_ID = "remote-app-config-v1"
    private const val KEY_NANA7MI_SEARCH = "nana7mi_search_enabled"
    private const val DEFAULT_NANA7MI_SEARCH = true
    private const val RETRY_COOLDOWN_MS = 5 * 60 * 1000L
}
