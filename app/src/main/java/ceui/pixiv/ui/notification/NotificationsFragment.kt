package ceui.pixiv.ui.notification

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Notification
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.web.LinkHandler

class NotificationsFragment : PixivFragment(R.layout.fragment_paged_list),
    NotificationActionReceiver {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<NotificationsFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs }) { args ->
        object : PagingAPIRepository<Notification>() {
            override suspend fun loadFirst(): KListShow<Notification> {
                return if (args.notificationId > 0L) {
                    Client.appApi.getViewMoreNotifications(args.notificationId)
                } else {
                    Client.appApi.getNotifications()
                }
            }

            override fun mapper(entity: Notification): List<ListItemHolder> {
                return listOf(NotificationHolder(entity))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
    }

    override fun onClickNotification(notification: Notification) {
        LinkHandler(findNavController(), this).processLink(notification.target_url)
    }

    override fun onClickViewMoreNotification(notification: Notification) {
        pushFragment(
            R.id.navigation_notification,
            NotificationsFragmentArgs(notification.id).toBundle()
        )
    }
}

