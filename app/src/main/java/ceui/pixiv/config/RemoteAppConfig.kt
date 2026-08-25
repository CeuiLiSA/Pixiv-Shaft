package ceui.pixiv.config

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.BuildConfig
import ceui.loxia.AppConfigResponse
import ceui.loxia.Client
import ceui.loxia.Nana7miPlan
import ceui.pixiv.push.InAppPushArrival
import com.google.gson.Gson
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
 * 冷启动向 pixshaft-api 拉一次的基础配置（`GET /v1/config`）：借号搜索总开关 + 订阅档位。
 *
 * 存在的意义是让服务端能在不发版的前提下关掉一个客户端功能，所以设计上只守两条：
 *
 * 1. **调用方永远不等网络。** 值先从 MMKV 读上一次的结果（首帧就有确定答案），拉取在后台
 *    跑完再覆盖。读接口是纯内存字段。
 * 2. **只有服务端明确说话才改。** 超时、5xx、字段缺失都保留上一次已知值，绝不因为一次网络
 *    抖动就把用户手里已经在用的功能掀掉。
 *
 * 从没成功拉到过时（首次安装、或服务端一直不可达）默认是**关**：这些开关管的是灰度中的功能，
 * 没拿到许可就不开，而不是先开着等服务端来喊停。
 *
 * uid 是灰度分桶键：服务端按它决定白/黑名单和灰度桶。因此登录态变化后要重拉一次，否则「这个
 * uid 该不该开」要等到下次冷启动才知道；这件事由 [nana7miSearchEnabled] 的读取顺带触发，不用
 * 在登录流程里另挂钩子。
 *
 * 进程级服务：由 [ceui.lisa.activities.Shaft] 构造并经 [ceui.loxia.ServicesProvider] 暴露，
 * 构造不做任何 IO，真正的读缓存 / 拉取从 [start] 开始。
 */
// Context 参数只为和其他进程级服务保持同一构造契约（见 ServicesProvider）；
// 本类只碰 MMKV，暂时用不上它。
class RemoteAppConfig(@Suppress("UNUSED_PARAMETER") app: Context) {

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

    /**
     * 这个 uid 的订阅档位，null = 还不知道。
     *
     * 和上面那个开关的缓存规矩**不一样**，因为它不是开关：
     *  - 开关拿不到就沿用旧值（网络抖动不该关掉用户正在用的功能）；
     *  - 档位是会**到期**的，沿用一份过期的缓存就是在骗人。所以持久化的那份带着到期时间，
     *    读出来先过一遍 [Nana7miPlan.ownedExpiresAt]，过期的直接当没有。
     *
     * 真正的读数还是以额度接口（`/v1/account/nana7mi/quota`）当场返回的为准，这里只负责让
     * 冷启动的第一帧就有一个答案。
     */
    @Volatile
    private var plan: Nana7miPlan? = null

    private val planLive = MutableLiveData<Nana7miPlan?>()

    /**
     * 档位变化的通知口。
     *
     * 侧边栏徽章必须观察它，不能只在「账号变化」时绑一次：冷启动这次配置是**异步**回来的，
     * 等它落地时账号那个 observer 早就跑完了 —— 首装的人于是永远看不到徽章，买了订阅的人
     * 要等下一次冷启动。刚买完从用量页退回来那次回写也走这里。
     */
    val nana7miPlanLive: LiveData<Nana7miPlan?>
        get() = planLive

    private val pushLive = MutableLiveData<InAppPushArrival?>()

    /**
     * 这次拉取捎回来的应用内推送（服务端 `inapp-push.js`），MainActivity 观察它弹一次框。
     *
     * 故意**不落 MMKV**：推送是「没回执过的才下发」，这台设备没展示成功（进程死了、没登录
     * 到首页）服务端下次冷启动会再给；落了缓存反而要自己处理过期和已读。LiveData 会对新
     * observer 重放最后一个值（转屏重建 Activity 那次），去重交给 [ceui.pixiv.push.InAppPushCenter]
     * 的本地已看名单。
     */
    val inAppPushLive: LiveData<InAppPushArrival?>
        get() = pushLive

    /** 最近一次成功拉取所用的 uid（0 = 未登录）；和当前登录态不一致就说明该重拉了。 */
    @Volatile
    private var fetchedForUid: Long? = null

    /** [nana7miSearch] 里现在装的是哪个 uid 的答案。灰度桶按 uid 分，不能张冠李戴。 */
    @Volatile
    private var valueForUid: Long? = null

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
            // Lite 是编译时硬隔离。即使旧缓存曾经写入 true、服务端配置错误或网络请求尚未
            // 返回，也不能给 Google/Lite 包一个借号窗口。
            if (BuildConfig.IS_LITE) return false
            refreshIfStale()
            return nana7miSearch
        }

    /**
     * 当前登录账号的订阅档位；没拉到过、未登录、或缓存已过期都是 null。
     *
     * null 是「不知道」，**不是「免费用户」** —— 拿它去判断该不该显示付费入口没问题，
     * 拿它去断言「这人没付钱」会冤枉刚装完还没拉到配置的订户。
     */
    val nana7miPlan: Nana7miPlan?
        get() {
            if (BuildConfig.IS_LITE) return null
            refreshIfStale()
            return plan
        }

    /**
     * 用一份更新的档位覆盖缓存。
     *
     * 额度接口（`/v1/account/nana7mi/quota`）每次都会捎回服务端此刻认的档位，比冷启动
     * 拉的那份新。刚买完订阅的人第一件事就是去用量页看，回写一下，抽屉里的徽章立刻就对了，
     * 不用等下一次冷启动。
     *
     * 只认当前登录账号的：切号后回来的旧响应不能盖到新账号头上。
     */
    fun updateNana7miPlan(uid: Long, incoming: Nana7miPlan?) {
        if (!initialized.get() || uid != SessionManager.loggedInUid) return
        applyPlan(uid, incoming)
    }

    /** 冷启动调用一次（幂等）。必须在 MMKV.initialize 和 SessionManager.initialize 之后。 */
    fun start() {
        if (!initialized.compareAndSet(false, true)) return
        // 同步读，让首帧就有确定答案；一次 mmap 读，不值得为它排一次调度。
        loadCached(SessionManager.loggedInUid)
        refreshIfStale()
    }

    /**
     * 把 [uid] 上次的已知答案搬进内存；这个 uid 没缓存过就是默认值（关）。
     *
     * 缓存**按 uid 分键**：服务端的灰度桶是按 uid 算的，A 号拿到的 true 不代表 B 号也该开。
     * 共用一个 key 的话，多账号用户切到 B 再冷启动，会在拉取返回前拿着 A 的许可去借号——正是
     * 这个开关要拦的事。分键还顺带让切回旧账号时立刻恢复它自己的答案。
     */
    private fun loadCached(uid: Long) {
        if (valueForUid == uid) return
        nana7miSearch = if (BuildConfig.IS_LITE) {
            false
        } else {
            runCatching {
                store.getBoolean(cacheKey(uid), DEFAULT_NANA7MI_SEARCH)
            }.getOrDefault(DEFAULT_NANA7MI_SEARCH)
        }
        plan = if (BuildConfig.IS_LITE) null else loadCachedPlan(uid)
        planLive.postValue(plan)
        valueForUid = uid
        Timber.tag(TAG).d(
            "loaded cached config uid=%d nana7mi_search_enabled=%s",
            uid,
            nana7miSearch,
        )
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
                // 会话中途切了账号：先把新 uid 上次的答案顶上（一次 mmap 读），别让接下来这一
                // 个网络往返期间继续用着上一个账号的许可。冷启动时 start 已经读过，这里是空转。
                loadCached(uid)
                apply(
                    uid,
                    Client.pixshaft.appConfig(
                        uid.takeIf { it > 0L },
                        BuildConfig.FLAVOR,
                    ),
                )
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
        applyPlan(uid, response.plan)
        // 推送和档位一样只给签了名的登录用户；Lite 连 plan 都拿不到，推送也一并屏蔽。
        val push = if (BuildConfig.IS_LITE || uid <= 0L) null else response.push
        pushLive.postValue(push?.let { InAppPushArrival(uid, it) })
        if (push != null) Timber.tag(TAG).i("in-app push arrived uid=%d id=%s", uid, push.id)
        val enabled = if (BuildConfig.IS_LITE) false else response.nana7miSearchEnabled
        if (enabled == null) {
            Timber.tag(TAG).d("server has no opinion on nana7mi search, keeping %s", nana7miSearch)
            return
        }
        nana7miSearch = enabled
        valueForUid = uid
        runCatching { store.putBoolean(cacheKey(uid), enabled) }
        Timber.tag(TAG).i("config applied uid=%d nana7mi_search_enabled=%s", uid, enabled)
    }

    /**
     * 档位单独走一条路：服务端**明确说了 null**（没签名 / 查不到）也要落地成 null，
     * 不能像开关那样「没意见就保留旧值」—— 那会让一个已经撤销的订阅在缓存里活下去。
     */
    private fun applyPlan(uid: Long, incoming: Nana7miPlan?) {
        plan = if (BuildConfig.IS_LITE) null else incoming?.takeIf { it.stillValid() }
        // postValue：这里跑在 IO 线程上（配置是后台拉的），LiveData 只能从主线程 setValue。
        planLive.postValue(plan)
        runCatching {
            if (plan == null) store.removeValueForKey(planKey(uid))
            else store.putString(planKey(uid), gson.toJson(plan))
        }
        Timber.tag(TAG).i("plan applied uid=%d owned=%s source=%s", uid, plan?.owned, plan?.source)
    }

    private fun loadCachedPlan(uid: Long): Nana7miPlan? = runCatching {
        store.getString(planKey(uid), null)
            ?.let { gson.fromJson(it, Nana7miPlan::class.java) }
            ?.takeIf { it.stillValid() }
    }.getOrNull()

    /**
     * 缓存里那份还算不算数。只看**买的那一档**的到期时间：计量档位可能是试运营给的，
     * 它没有到期日，跟着服务端走就行；而买的那一档过了期，本地就该忘掉它。
     */
    private fun Nana7miPlan.stillValid(): Boolean {
        val until = ownedExpiresAt ?: return true
        return until > System.currentTimeMillis()
    }

    private fun cacheKey(uid: Long) = KEY_NANA7MI_SEARCH_PREFIX + uid

    private fun planKey(uid: Long) = KEY_NANA7MI_PLAN_PREFIX + uid

    private val gson by lazy { Gson() }

    private companion object {
        const val TAG = "RemoteAppConfig"
        const val MMKV_ID = "remote-app-config-v1"
        const val KEY_NANA7MI_SEARCH_PREFIX = "nana7mi_search_enabled_"
        const val KEY_NANA7MI_PLAN_PREFIX = "nana7mi_plan_"
        // 没拿到服务端许可之前不开：这是灰度中的功能，默认关比默认开安全。
        const val DEFAULT_NANA7MI_SEARCH = false
        const val RETRY_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
