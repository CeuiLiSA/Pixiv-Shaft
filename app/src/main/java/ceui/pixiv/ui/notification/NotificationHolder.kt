package ceui.pixiv.ui.notification

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.databinding.CellNotificationBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.DateParse
import ceui.loxia.NotificationItem
import ceui.pixiv.feeds.FeedItem
import com.bumptech.glide.RequestManager

/** feeds 框架条目，被 [NotificationListFragment] 和 [NotificationViewMoreFragment] 共用。 */
data class NotificationFeedItem(val item: NotificationItem) : FeedItem {
    override val feedKey: Any get() = item.id
}

/**
 * 通知 cell 的实际渲染逻辑，两处 feeds renderer 共用，避免逻辑漂移。
 *
 * 只画内容、不挂点击监听：按框架约定监听在 renderer 的 `create` 里挂一次（用 `cell.item` 取
 * 当下条目），bind 阶段每次都 setOnClick 会在 fling 帧路径上白白分配一堆 lambda。
 * 图片用调用方传进来的 [glide]（每页建一次复用），配套的 `recycle` 负责清请求，
 * 否则复用的 holder 会先闪一下上一条通知的图。
 */
fun CellNotificationBinding.bindNotification(
    item: NotificationItem,
    glide: RequestManager,
) {
    val context = root.context
    val content = item.content

    // 文本:服务端给的是带 <b> 的 HTML,直接交给 HtmlCompat。
    // 用户名加粗就是这条 <b>;不另算 span。
    notificationText.text = HtmlCompat.fromHtml(
        content?.text.orEmpty(),
        HtmlCompat.FROM_HTML_MODE_COMPACT,
    )

    // 时间:复用 DateParse 的"x 分钟前 / N 天前 / yyyy-MM-dd",通知一般是近期事件,
    // getTimeAgo 输出更友好。
    notificationTime.text = DateParse.getTimeAgo(context, item.created_datetime)

    // 头像:优先 left_image(作品方图),其次 left_icon(头像),都没有时挂占位。
    val avatarUrl = content?.left_image ?: content?.left_icon
    if (!avatarUrl.isNullOrEmpty()) {
        glide.load(GlideUrlChild(avatarUrl))
            .placeholder(R.drawable.chat_avatar_placeholder)
            .into(leftAvatar)
    } else {
        glide.clear(leftAvatar)
        leftAvatar.setImageResource(R.drawable.chat_avatar_placeholder)
    }

    // 右侧缩略图:right_image 是作品方图,有就显示
    val rightUrl = content?.right_image ?: content?.right_icon
    if (!rightUrl.isNullOrEmpty()) {
        rightThumb.isVisible = true
        glide.load(GlideUrlChild(rightUrl))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(rightThumb)
    } else {
        glide.clear(rightThumb)
        rightThumb.isVisible = false
    }

    // 未读 accent:item.is_read=false 或 group 内有未读时显示
    val isUnread = !item.is_read || item.view_more?.unread_exists == true
    unreadAccent.isVisible = isUnread

    // view_more chip:分组通知才有,文案直接用服务端 title(如 "有新粉丝")
    val groupTitle = item.view_more?.title
    if (groupTitle != null) {
        viewMoreButton.isVisible = true
        viewMoreButton.text = context.getString(R.string.notification_view_more_chip, groupTitle)
    } else {
        viewMoreButton.isVisible = false
    }
}
