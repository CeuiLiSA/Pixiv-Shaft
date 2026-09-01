package ceui.pixiv.session

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.SelfProfile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动后拉一次 /v1/user/me/state（self profile / 账号状态）。
 *
 * 纯 fire-and-forget：一次冷启动只发一次、永远在 IO 线程、**任何条件下都不崩溃**。
 * 结果目前没有消费方（模型 [SelfProfile] 已补齐全字段，见 Models.kt），只做预热/占位；
 * 将来要真正读账号状态从这里接线即可。
 *
 * 崩溃防线共四层，任意一层单独都足以吞住异常：
 *  1. [fired] 幂等，重复调用直接返回（虽然挂载点 runDeferredInit 本身已幂等）；
 *  2. [scope] 顶层 [SupervisorJob]，子协程异常不向上传播；
 *  3. [CoroutineExceptionHandler] 兜住协程未捕获异常并转成一条日志；
 *  4. 请求本体再包一层 [runCatching]，401 / 网络错 / 解析错全部落这里。
 *
 * 调用方（Shaft.runDeferredInit 的 step）外面还有一层 try/catch，是第五层。
 */
object SelfProfileWarmup {

    private const val TAG = "SelfProfileWarmup"

    /** 进程内幂等标志：一次冷启动只发一次。 */
    private val fired = AtomicBoolean(false)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    Timber.tag(TAG).w(error, "self profile warmup crashed")
                },
    )

    /**
     * 触发一次预热。可在任意线程调用（内部立刻切到 IO），本身几乎不可能抛：
     * 只做一次 CAS 和一次 launch。
     */
    @JvmStatic
    fun trigger() {
        if (!fired.compareAndSet(false, true)) return
        scope.launch {
            // 未登录时该接口必定 401，发它没有意义。LiveData.value 允许任意线程读，
            // 仍用 runCatching 兜一层，绝不让判断本身把预热搞崩。
            val loggedIn = runCatching { SessionManager.isLoggedIn }.getOrDefault(false)
            if (!loggedIn) {
                Timber.tag(TAG).d("skip self profile warmup: not logged in")
                return@launch
            }
            runCatching {
                val profile: SelfProfile = Client.appApi.getSelfProfile()
                // profile.is_premium 是**绝对权威**的会员状态（和 user/detail 同源，刚真读到）。
                // 缺字段（null）就不报——宁可不报，也不拿一个不确定的值去替号作证。
                val premium = profile.profile.is_premium
                if (premium == null) {
                    Timber.tag(TAG).d("self profile warmed but is_premium missing, skip report")
                    return@runCatching
                }
                // 用响应自带的权威 user_id 作为这份 premium 的归属，交给 SessionManager 再校验
                // 它是否仍是当前登录号——请求在途切了号就不会把这份 premium 安到别人号上。
                val authoritativeUid = profile.profile.user_id
                Timber.tag(TAG).d(
                    "self profile warmed: uid=%d is_premium=%s",
                    authoritativeUid, premium,
                )
                // 上报走登录会话线，必须在主线程调（更新 LiveData + 与切号 setValue 串行）。
                withContext(Dispatchers.Main) {
                    SessionManager.reportAuthoritativePremium(profile.profile, authoritativeUid, premium)
                }
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "self profile warmup request failed")
            }
        }
    }
}
