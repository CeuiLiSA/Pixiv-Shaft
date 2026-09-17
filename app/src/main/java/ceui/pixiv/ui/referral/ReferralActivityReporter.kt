package ceui.pixiv.ui.referral

import ceui.lisa.activities.Shaft
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 「这个账号今天收藏过」的信标。
 *
 * 为什么非要客户端报：收藏发生在 Pixiv，pixshaft 的服务器完全不在那条链路上，它看不见。
 * 而「有效邀请」的判定里有这一条（7 天内两个自然日在用 + 至少收藏过一次），所以只能由
 * 这里说一声。
 *
 * 这个 bit **造不出一个活跃日来** —— 服务端只用它给一个本来就有服务端流量的自然日打标记。
 * 伪造它也省不下什么：真要刷，得先有一个能登录的 Pixiv 账号、装上 App、连着用两天。成本
 * 在账号，不在这个 bit。
 *
 * 三条约束，改这里时别丢：
 *  - **一天最多一次。** 判定只需要「这天收藏过」这一个布尔值，第二次报没有任何新信息，
 *    而收藏是连点最多的操作之一。
 *  - **完全静默。** 它挂在收藏的成功路径上，失败不能打断用户正在做的事，也不能弹任何
 *    东西。今天漏了，下次收藏会再报。
 *  - **活动关着就不发。** 没有活动时这条路由的结果没人会读，不值得为它打一次网络。
 */
object ReferralActivityReporter {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )
    private val repository by lazy { ReferralRepository() }

    /** 上一次上报落在哪一天（按设备本地时区切；精确到天就够，不必和服务端的时区对齐）。 */
    private val reportedDay = AtomicLong(-1L)

    /** 用户刚收藏了一次。取消收藏不算 —— 判定说的是「收藏过」。 */
    fun onBookmark() {
        if (!Shaft.getContext().appServices().remoteAppConfig.referralEnabled) return
        if (!SessionManager.isLoggedIn) return
        val today = System.currentTimeMillis() / 86_400_000L
        val previous = reportedDay.get()
        if (previous == today) return
        // 收藏会在不同线程上连点。CAS 输了说明另一个线程正在发同一条，就不发第二条了。
        if (!reportedDay.compareAndSet(previous, today)) return
        scope.launch { repository.reportBookmark() }
    }
}
