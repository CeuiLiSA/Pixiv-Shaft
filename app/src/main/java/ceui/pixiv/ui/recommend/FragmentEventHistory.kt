package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.loxia.appServices
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 操作记录（feeds 框架版）— 调 shaft-api-v2 /events/history 拉当前 client_id 的事件流，
 * 不分类型（全部 bookmark/download/follow 按时间倒序），翻页用 next_before 游标。
 * 只读列表：刷新/翻页/空态全部交给 [FeedFragment]，数据在 [EventHistoryFeedSource]。
 * client_id 未生成时数据源返回空页 → 显示 empty 占位。
 */
class FragmentEventHistory : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<Long> {
        EventHistoryFeedSource(requireContext().appServices().eventReporter)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(eventHistoryRenderer())

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.event_history)
    }
}
