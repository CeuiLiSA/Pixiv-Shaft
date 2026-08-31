package ceui.pixiv.ui.notification

import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNotificationBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.NotificationItem
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * "通知" tab 内容页（feeds 框架版）。父 NotificationPagerFragment 自带 toolbar + tab bar
 * （实现 ViewPagerFragment），本页用裸 fragment_feed（默认 contentLayoutId），不需要自己的
 * toolbar，也就不需要旧版 PixivFragment.setUpToolbar 那套"是不是 ViewPager 子页"判断。
 */
class NotificationListFragment : FeedFragment() {

    override val feedViewModel by feedViewModels<String> {
        pixivFeedSource(initialFetch = { Client.appApi.getNotificationList() }) { resp, _ ->
            resp.displayList.map { NotificationFeedItem(it) }
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 12dp = 卡片左右 inset + 卡片间纵向间距,统一走 ItemDecoration,
        // cell layout 不再背 margin。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(notificationRenderer())
    }

    /** 头像 / 缩略图的 Glide 请求管理器，建一次复用（对齐各列表页的 pinHostGlide 惯例）。 */
    private val notificationGlide: RequestManager by lazy { Glide.with(this) }

    private fun notificationRenderer() = feedRenderer<NotificationFeedItem, CellNotificationBinding>(
        inflate = CellNotificationBinding::inflate,
        create = { cell ->
            // 监听在 create 挂一次（框架约定），点击时用 cell.item 取当下条目
            cell.binding.notificationRoot.setOnClick {
                val item = cell.itemOrNull?.item ?: return@setOnClick
                requireContext().routeNotificationTargetUrl(item.target_url)
            }
            cell.binding.viewMoreButton.setOnClick {
                val item = cell.itemOrNull?.item ?: return@setOnClick
                onClickViewMore(item)
            }
        },
        recycle = { cell ->
            notificationGlide.clear(cell.binding.leftAvatar)
            notificationGlide.clear(cell.binding.rightThumb)
        },
    ) { cell ->
        cell.binding.bindNotification(cell.item.item, notificationGlide)
    }

    private fun onClickViewMore(item: NotificationItem) {
        if (item.id <= 0L) return
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOTIFICATION_VIEW_MORE.key)
            putExtra(NotificationViewMoreFragment.EXTRA_NOTIFICATION_ID, item.id)
            putExtra(NotificationViewMoreFragment.EXTRA_TITLE, item.view_more?.title.orEmpty())
        }
        startActivity(intent)
    }
}
