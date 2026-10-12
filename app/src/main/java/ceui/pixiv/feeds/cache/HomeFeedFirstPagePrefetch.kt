package ceui.pixiv.feeds.cache

import ceui.pixiv.api.model.HomeIllustResponse
import kotlinx.coroutines.runBlocking

/**
 * 冷启动预取「首页推荐」首屏快照。
 *
 * 为什么需要：开屏（splash）要等首页 feed 的「本地优先」快照才能放行，而那段实测是
 * Room 读 ~116ms + JSON 解析 ~168ms（全在后台线程）。它原本要等 Fragment 的
 * onViewCreated 才起跑——比首帧还晚几十毫秒，于是主线程画完首帧后空等近 300ms。
 * 这里在 Activity.onCreate 就把它跑起来，和后面建视图 / 建底栏 / 首帧并行。
 *
 * 只在「启动页确实是首页推荐 tab」时调用（见 MainActivity.onCreate 的判定）——
 * 其它启动页根本不走这条开屏门控，预取了也是白跑。
 */
object HomeFeedFirstPagePrefetch {

    /**
     * 首页推荐 tab 第一条内容页的 slot。
     *
     * 固定值：FragmentLeft 建它时写死 TYPE_ILLUST（不跟设置走），所以恒为 `recmd-illust`。
     */
    private const val SLOT = "recmd-illust"

    /** 起过就不再起（warm 可能被多处调用）。 */
    @Volatile
    private var started = false

    /**
     * 起一条**低优先级**专用线程把首屏读出来。
     *
     * 为什么必须低优先级：解析实测会把主线程首帧的 FragmentLeft 视图构建拖慢 ~75ms（抢 CPU）。
     * 而解析本身有 ~440ms 余量（预取 T0+776 起跑、feed 到 T0+1220 才读），慢一点无所谓——
     * 用 THREAD_PRIORITY_BACKGROUND 让调度器永远优先喂主线程。
     *
     * 解析走 [FeedFirstPageCache.prefetch] 的 `onCallerThread = true` 路径：不切
     * Dispatchers.Default，否则优先级会丢在 Default 池上，等于没降。
     */
    fun warm() {
        if (started) return
        started = true
        try {
            Thread(
                Runnable {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND,
                    )
                    try {
                        runBlocking {
                            FeedFirstPageCache.prefetch(
                                feedFirstPageCache(SLOT, HomeIllustResponse::class.java),
                            )
                        }
                    } catch (t: Throwable) {
                        // 预取纯属提前量：失败就走原来的同步读取路径。
                    }
                },
                "shaft-home-cache-prefetch",
            ).start()
        } catch (t: Throwable) {
            // 起不了线程就退回原来的路径。
        }
    }
}