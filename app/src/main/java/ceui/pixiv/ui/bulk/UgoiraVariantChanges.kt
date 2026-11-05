package ceui.pixiv.ui.bulk

import ceui.pixiv.communication.CommunicationScope
import ceui.pixiv.communication.EventTopic
import ceui.pixiv.communication.EventTopicConfig

/**
 * 「动图该播哪一版帧序列」变了：补帧开关切换、删 RIFE 模型、清动图缓存。
 * 由 [UgoiraEngine.invalidateAll] 发布，正在播的 [ceui.pixiv.ui.detail.UgoiraPlayerView] 订阅后按新开关重取。
 *
 * 瞬时通知、不补发：没在播的播放器（暂停 / 离屏 / 页面不在前台）下次恢复时本来就会重取，用不着历史。
 */
data object UgoiraVariantChanged

/** 组合根（Shaft）创建一次；发布端和订阅端分别经 ServicesProvider 交给引擎与播放器。 */
fun CommunicationScope.ugoiraVariantChangeTopic(): EventTopic<UgoiraVariantChanged> =
    eventTopic(EventTopicConfig(debugName = "ugoira-variant-changes"))
