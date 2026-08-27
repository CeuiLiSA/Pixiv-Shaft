package ceui.pixiv.session

import timber.log.Timber

/**
 * access token 刷新的单飞（single-flight）协调器。
 *
 * 场景：App 回前台时 feed/详情/用户/榜单会同时 401，N 个 OkHttp 线程同时要求刷新。
 * pixiv 的 refresh_token 是轮换的——N 个线程都拿同一个旧 refresh_token 去打
 * /auth/token，只有第一个成功，其余收到 "Invalid refresh token"，会被误判成凭证吊销
 * 而强制登出。所以同一个旧 token 只允许一个线程真正发刷新请求，其余排队后直接
 * 拿它的结果。
 *
 * 刷新**失败**时（网络断了）排队的线程也不接力重试：否则 N 个等锁线程会在 OkHttp
 * 线程上串行吃 N 次超时。它们各自放弃（本次请求照旧 400），下一次新来的 401 再试。
 *
 * 纯逻辑，不碰 LiveData / MMKV / 网络，便于单测；[SessionManager] 负责注入两端。
 *
 * @param currentToken 读「此刻」的 access token；已登出时返回 null。
 * @param doRefresh    真正的刷新动作（阻塞）；成功返回新 access token，失败返回 null。
 *                     调用方保证它返回时 [currentToken] 已能读到新值。
 */
class SingleFlightTokenRefresher(
    private val currentToken: () -> String?,
    private val doRefresh: () -> String?,
) {
    private val lock = Any()

    /** 已完成的刷新尝试次数（成功失败都算）。只在 [lock] 内改。 */
    @Volatile
    private var completedAttempts = 0L

    /**
     * @param staleToken 触发 401 的那次请求所用的 access token。
     * @return 可用的新 access token；拿不到（已登出 / 刷新失败）返回 null。
     */
    fun refresh(staleToken: String): String? {
        val t = Thread.currentThread().name
        val stale = staleToken.tail()
        // 无锁快速路径：别人已经换过了
        val fast = currentToken()
        if (fast == null) {
            Timber.tag(TAG).d("[%s] stale=%s fast-path: logged out → null", t, stale)
            return null
        }
        if (fast != staleToken) {
            Timber.tag(TAG).d("[%s] stale=%s fast-path: already refreshed → %s", t, stale, fast.tail())
            return fast
        }

        val attemptsWhenQueued = completedAttempts
        Timber.tag(TAG).d("[%s] stale=%s queueing for lock (attempts=%d)", t, stale, attemptsWhenQueued)
        val waitStart = System.nanoTime()
        synchronized(lock) {
            val waitedMs = (System.nanoTime() - waitStart) / 1_000_000
            // 双重检查：排队等锁期间，前一个持锁线程大概率已刷完
            val current = currentToken()
            if (current == null) {
                Timber.tag(TAG).d("[%s] stale=%s got lock after %dms: logged out → null", t, stale, waitedMs)
                return null
            }
            if (current != staleToken) {
                Timber.tag(TAG).d("[%s] stale=%s got lock after %dms: refreshed by another thread → %s", t, stale, waitedMs, current.tail())
                return current
            }
            // token 没变、但排队期间有人试过了 —— 那次失败了，别接力
            if (completedAttempts != attemptsWhenQueued) {
                Timber.tag(TAG).w("[%s] stale=%s got lock after %dms: an attempt already failed meanwhile, giving up → null", t, stale, waitedMs)
                return null
            }
            Timber.tag(TAG).i("[%s] stale=%s got lock after %dms: REFRESHING (attempt #%d)", t, stale, waitedMs, completedAttempts + 1)
            val start = System.nanoTime()
            try {
                val result = doRefresh()
                val ms = (System.nanoTime() - start) / 1_000_000
                if (result != null) {
                    Timber.tag(TAG).i("[%s] stale=%s refresh OK in %dms → %s", t, stale, ms, result.tail())
                } else {
                    Timber.tag(TAG).w("[%s] stale=%s refresh FAILED in %dms → null", t, stale, ms)
                }
                return result
            } finally {
                completedAttempts++
            }
        }
    }

    private fun String.tail(): String = if (length <= 6) this else "…" + takeLast(6)

    private companion object {
        const val TAG = "TokenRefresh"
    }
}
