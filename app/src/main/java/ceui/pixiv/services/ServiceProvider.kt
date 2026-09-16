package ceui.pixiv.services

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import ceui.lisa.viewmodel.AppLevelState
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.actions.Nana7miSearchTelemetry
import ceui.pixiv.actions.PixivActionQueue
import ceui.pixiv.config.RemoteAppConfig
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.db.mirror.BookmarkMirrorService
import ceui.pixiv.events.EventReporter
import ceui.pixiv.ui.bulk.QueueDownloadManager
import ceui.pixiv.ui.fanbox.FanboxWebBridge
import ceui.pixiv.ui.translate.MangaBatchTranslateCenter
import ceui.pixiv.ui.translate.MangaTranslateModels
import ceui.pixiv.utils.NetworkStateManager
import com.tencent.mmkv.MMKV


/**
 * 进程级服务的唯一出口。原则：
 *
 * - 这里登记的都是「一个进程只该有一份」的服务，由 [ceui.lisa.activities.Shaft] 在
 *   onCreate 里 **构造** 并持有；类本身是普通 class，可以在单测里 new 出第二份。
 * - 构造函数必须廉价（不做 IO、不起协程），真正的工作延迟到第一次调用。
 * - 想拿服务：有 Context 用 [Context.appServices]，Fragment/Activity 用 requireXxx()。
 *   不要再新增 Kotlin `object` 单例来承载有状态的服务。
 */
interface ServicesProvider {
    /** 通用进程内通信容器；业务在组合根创建类型化 topic，再按读写能力注入。 */
    val communicationScope: ceui.pixiv.communication.CommunicationScope

    val prefStore: MMKV
    val networkStateManager: NetworkStateManager
    val entityWrapper: EntityWrapper

    /** 关注/收藏态的进程级内存表（原 static `Shaft.appViewModel`）。 */
    val appLevelState: AppLevelState

    /** 漫画翻译用的 OCR / 文本检测模型会话，批量与单页共用一份。 */
    val mangaTranslateModels: MangaTranslateModels

    /** 整本漫画批量翻译任务（跨 Activity 存活，所以是进程级）。 */
    val mangaBatchTranslateCenter: MangaBatchTranslateCenter

    /** FANBOX post.info 只能从 WebView 发，这个桥持有那个不上屏的 WebView，可按需释放。 */
    val fanboxWebBridge: FanboxWebBridge

    /** 批量下载持久化队列的消费循环（v33）。 */
    val queueDownloadManager: QueueDownloadManager

    /** 收藏/关注等写操作的离线队列。 */
    val pixivActionQueue: PixivActionQueue

    /** 发现页用户画像（关注/收藏/标签打分）。 */
    val profileManager: ProfileManager

    /** 发现页候选池。 */
    val discoveryPool: DiscoveryPool

    /** 账号在线/失效上报的离线信箱。 */
    val accountOnlineReportOutbox: AccountOnlineReportOutbox

    /** nana7mi 搜索埋点（批量攒发）。 */
    val nana7miSearchTelemetry: Nana7miSearchTelemetry

    /** 服务端下发的远程配置（按 uid 缓存）。 */
    val remoteAppConfig: RemoteAppConfig

    /** shaft-events 埋点上报。 */
    val eventReporter: EventReporter

    /** 收藏镜像引擎：限速静默地把收藏列表整份镜像到本地，支撑倒序与花式筛选。 */
    val bookmarkMirror: BookmarkMirrorService
}

fun Context.appServices(): ServicesProvider = applicationContext as ServicesProvider

fun Fragment.requireEntityWrapper(): EntityWrapper {
    return requireActivity().requireEntityWrapper()
}

fun FragmentActivity.requireEntityWrapper(): EntityWrapper {
    return (application as ServicesProvider).entityWrapper
}

fun Fragment.requireNetworkStateManager(): NetworkStateManager {
    return requireActivity().requireNetworkStateManager()
}

fun FragmentActivity.requireNetworkStateManager(): NetworkStateManager {
    return (application as ServicesProvider).networkStateManager
}
