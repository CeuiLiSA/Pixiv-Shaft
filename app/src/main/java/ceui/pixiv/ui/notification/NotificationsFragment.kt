package ceui.pixiv.ui.notification

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.Notification
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel

class NotificationsFragment : PixivFragment(R.layout.fragment_pixiv_list),
    NotificationActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<NotificationsFragmentArgs>()
    private val viewModel by pixivListViewModel({ safeArgs }) { args ->
        DataSource(
            dataFetcher = {
                if (args.notificationId > 0L) {
                    Client.appApi.getViewMoreNotifications(args.notificationId)
                } else {
                    Client.appApi.getNotifications()
                }
            },
            itemMapper = { notification -> listOf(NotificationHolder(notification)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
    }

    override fun onClickNotification(notification: Notification) {

    }

    override fun onClickViewMoreNotification(notification: Notification) {
        pushFragment(
            R.id.navigation_notification,
            NotificationsFragmentArgs(notification.id).toBundle()
        )
    }
}

