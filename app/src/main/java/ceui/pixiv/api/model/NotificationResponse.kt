package ceui.pixiv.api.model

import java.io.Serializable

/**
 * /v1/notification/list 和 /v1/notification/view-more 共用同一种 envelope。
 * view-more 是把某个 group(view_more 不为 null 的 NotificationItem) 摊平的子列表，
 * 没有自己的字段差异。
 */
data class NotificationListResponse(
    val notifications: List<NotificationItem> = listOf(),
    val next_url: String? = null,
) : Serializable, KListShow<NotificationItem> {
    override val displayList: List<NotificationItem> get() = notifications
    override val nextPageUrl: String? get() = next_url
}

/**
 * - [type]：iOS pixiv app 抓包见过 7(收藏)/8(关注)；客户端只用来当 hint，
 *   渲染 100% 看 [content].text 里的 HTML 加粗。未来出新类型不会破。
 * - [target_url]：永远是 pixiv:// scheme（illusts/users/novels），我们 in-app 路由。
 * - [view_more]：非空表示"这条是 group 头"，点 view_more 按钮调
 *   /v1/notification/view-more?notification_id=[id] 拉完整子列表。
 */
data class NotificationItem(
    val id: Long = 0L,
    val created_datetime: String? = null,
    val type: Int = 0,
    val content: NotificationContent? = null,
    val view_more: NotificationViewMore? = null,
    val target_url: String? = null,
    val is_read: Boolean = true,
) : Serializable

data class NotificationContent(
    val text: String? = null,
    val left_icon: String? = null,
    val left_image: String? = null,
    val right_icon: String? = null,
    val right_image: String? = null,
) : Serializable

data class NotificationViewMore(
    val unread_exists: Boolean = false,
    val title: String? = null,
) : Serializable
