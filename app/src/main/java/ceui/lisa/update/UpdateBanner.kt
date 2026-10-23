package ceui.lisa.update

import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.banner.BannerAction
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.host.InAppBanners

/**
 * 「更新包下好了，但用户已经把 sheet 关掉」时的兜底提示。
 *
 * <p>下载是进程级的（[ApkDownloadManager]），sheet 只是订阅者：用户点「稍后」把 sheet
 * 关掉之后下载照旧跑，跑完那一刻没有 UI 能告诉他。这里负责把这条消息变成一条
 * in-app banner，点一下回到更新 sheet（deep link `shaft://update`）。
 *
 * <p><b>只在应用处于前台时发</b>。后台发出去的东西用户看不见，等切回前台时再发，
 * 免得它和「刚回到应用」这一帧的其他内容抢位置。
 */
object UpdateBanner {

    private const val ID = "update-apk-ready"

    /** banner 点击后由 [InAppBanners] 解析，见 `shaft://update`。 */
    const val DEEP_LINK = "shaft://update"

    @Volatile
    private var pending = false

    /** 下载完成且当时没有 sheet 订阅。 */
    fun markPending() {
        pending = true
        maybePost()
    }

    /** 有 Activity 回到前台：如果还欠一条提示，现在发。 */
    fun onForeground() {
        maybePost()
    }

    /** banner 系统刚就绪（冷启动时它晚于首个 onResume）。 */
    fun onBannerReady() {
        maybePost()
    }

    /** sheet 已经打开（或用户已安装）：撤掉欠着的提示和屏幕上那条。 */
    fun clear() {
        pending = false
        if (!InAppBanners.isReady()) return
        runCatching { InAppBanners.manager.dismiss(ID) }
    }

    private fun maybePost() {
        if (!pending) return
        val state = ApkDownloadManager.state.value
        if (state.phase != ApkDownloadManager.Phase.COMPLETED) {
            pending = false
            return
        }
        // 后台不发：等下一次 onActivityResumed 再来。
        if (InAppBanners.currentActivity() == null) return
        // banner 系统还没 bootstrap（冷启动的延迟批），等它就绪的通知。
        if (!InAppBanners.isReady()) return
        val context = Shaft.getContext() ?: return
        val version = state.versionName

        pending = false
        ApkDownloadManager.consumeBannerPending()
        runCatching {
            InAppBanners.manager.enqueue(
                BannerRequest.Text(
                    id = ID,
                    title = context.getString(R.string.update_banner_done_title),
                    message = version?.let {
                        context.getString(R.string.update_banner_done_message, it)
                    },
                    caption = context.getString(R.string.update_download_title),
                    action = BannerAction(
                        label = context.getString(R.string.update_banner_action),
                        deepLink = DEEP_LINK,
                    ),
                    dedupKey = ID,
                    priority = BannerPriority.HIGH,
                    category = BannerCategory.System,
                    // 比默认 4 秒长一点：这是一条要用户做决定的提示，不是路过通知。
                    autoDismissMillis = 8_000L,
                    deepLink = DEEP_LINK,
                )
            )
        }
    }
}
