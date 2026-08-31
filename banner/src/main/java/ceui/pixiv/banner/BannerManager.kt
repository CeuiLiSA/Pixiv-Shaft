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
}
