package ceui.pixiv.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNotificationBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.NotificationItem
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * "展开全部" 子页（feeds 框架版）:从某条 group 通知点开,拉同 group 的完整流水。
 * 标题用 group 自身的 view_more.title,通过宿主 Activity 的 intent extra 传入
 * ——TemplateActivity 用无参构造创建这个 Fragment,不走 Fragment arguments。
 */
class NotificationViewMoreFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "notification_view_more_title"
    }

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val notificationId: Long by lazy {
        requireActivity().intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0L)
    }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获约定:先取成局部 val 再给 PixivFeedSource 用,不捕获 Fragment 本身。
        val id = notificationId
        pixivFeedSource(initialFetch = { Client.appApi.getNotificationViewMore(id) }) { resp, _ ->
            resp.displayList.map { NotificationFeedItem(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        val title = requireActivity().intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbarTitle.text = title.ifEmpty { getString(R.string.tab_notifications) }
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 ListMode.VERTICAL_NO_MARGIN + 手动挂的 12dp decoration。
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
        // 子页里的 cell 理论上不再有 view_more,但兜底也跳一次自身。
        if (item.id <= 0L || item.id == notificationId) return
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOTIFICATION_VIEW_MORE.key)
            putExtra(EXTRA_NOTIFICATION_ID, item.id)
            putExtra(EXTRA_TITLE, item.view_more?.title.orEmpty())
        }
        startActivity(intent)
    }
}
