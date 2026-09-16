package ceui.pixiv.banner

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BannerManager {

    val state: StateFlow<BannerState>
    val events: SharedFlow<BannerEvent>
    val queueSize: StateFlow<Int>

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
