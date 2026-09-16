package ceui.pixiv.banner

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BannerManager {

    val state: StateFlow<BannerState>
    val events: SharedFlow<BannerEvent>
    val queueSize: StateFlow<Int>

    /**
     * 当前有没有 STARTED 的宿主能真的把 banner 画出来。
     *
     * 给**入队方**判「现在弹得出来吗」用：无宿主时入队的请求会被留到下一个宿主
     * （见 [onHostStarted]），对一次性引导是对的，但对「只在当下有意义」的提示
     * （聊天消息）就是补显 —— 那种提示应该在这里自己抑制掉，而不是让 manager
     * 为它破坏保留语义。
     */
    val hasStartedHost: StateFlow<Boolean>

    fun enqueue(request: BannerRequest): Boolean
    fun dismiss(id: String, reason: BannerDismissReason = BannerDismissReason.Programmatic)
    fun dismissCategory(category: BannerCategory)
    fun clearAll()
    fun start()
    fun shutdown()
    fun notifyTapped(id: String)
    fun notifyActionTapped(id: String, actionKey: String?)
    fun binderFor(key: String): BannerViewBinder?

    /**
     * 一个 [BannerPresenter] 进入 STARTED，宿主从此可以接收 state 并渲染。
     * 必须在主线程调用。
     */
    fun onHostStarted()

    /**
     * 一个 [BannerPresenter] 离开 STARTED。最后一个宿主离开时，已经展示中的请求
     * 按原来的 dismiss 语义继续；尚未开始的请求留在队列里等下一个 STARTED 宿主。
     * 必须在主线程调用。
     */
    fun onHostStopped()
}
