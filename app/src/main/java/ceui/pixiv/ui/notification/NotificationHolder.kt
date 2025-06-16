package ceui.pixiv.ui.notification

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNotificationBinding
import ceui.loxia.Notification
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.user.binding_loadMedia
import ceui.pixiv.utils.setOnClick

class NotificationHolder(val notification: Notification) : ListItemHolder() {
    override fun getItemId(): Long {
        return notification.id
    }
}

@ItemHolder(NotificationHolder::class)
class NotificationViewHolder(private val bd: CellNotificationBinding) :
    ListItemViewHolder<CellNotificationBinding, NotificationHolder>(bd) {

    override fun onBindViewHolder(holder: NotificationHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.text.text = HtmlCompat.fromHtml(
            holder.notification.content?.text ?: "",
            HtmlCompat.FROM_HTML_MODE_COMPACT
        )

        binding.leadIcon.binding_loadMedia(
            holder.notification.content?.left_icon ?: holder.notification.content?.left_image
        )

        binding.root.setOnClick {
            it.findActionReceiverOrNull<NotificationActionReceiver>()
                ?.onClickNotification(holder.notification)
        }

        binding.showMore.isVisible = holder.notification.view_more != null
        binding.showMore.setOnClick {
            it.findActionReceiverOrNull<NotificationActionReceiver>()
                ?.onClickViewMoreNotification(holder.notification)
        }
    }
}

interface NotificationActionReceiver {
    fun onClickNotification(notification: Notification)
    fun onClickViewMoreNotification(notification: Notification)
}