package ceui.pixiv.ui.referral

import ceui.lisa.activities.Shaft
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「这个账号今天收藏过」的信标。
 *
 * 为什么非要客户端报：收藏发生在 Pixiv，pixshaft 的服务器完全不在那条链路上，它看不见。
 * 而「有效邀请」的判定里有这一条（7 天内两个自然日在用 + 至少收藏过一次），所以只能由
 * 这里说一声。
 *
 * 服务端会把收藏成功信标计入当天活跃。客户端信标不能独立证明 Pixiv 收藏，
 * 防刷仍需要服务端验证账号归属并复核异常参与。
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

    private val gate = ReferralBookmarkGate()

    /** 仅由队列的成功回调调用，uid 是动作入队时的归属。 */
    fun onBookmark(uid: Long) {
        if (!Shaft.getContext().appServices().remoteAppConfig.referralEnabled) return
        if (!SessionManager.isLoggedIn || uid != SessionManager.loggedInUid) return
        val day = (System.currentTimeMillis() + 8 * 3_600_000L) / 86_400_000L
        if (!gate.begin(uid, day)) return
        scope.launch {
            var success = false
            try {
                if (uid == SessionManager.loggedInUid) success = repository.reportBookmark(uid)
            } finally {
                gate.complete(uid, day, success)
            }
        }
    }
}

/** 同账号、同自然日去重；失败释放占位，其他账号的收藏不会被吞掉。 */
internal class ReferralBookmarkGate {
    private val inFlight = mutableSetOf<Pair<Long, Long>>()
    private val reported = mutableSetOf<Pair<Long, Long>>()

    @Synchronized fun begin(uid: Long, day: Long): Boolean {
        reported.removeAll { it.second != day }
        val key = uid to day
        return key !in reported && inFlight.add(key)
    }

    @Synchronized fun complete(uid: Long, day: Long, success: Boolean) {
        val key = uid to day
        inFlight.remove(key)
        if (success) reported.add(key)
    }
}
