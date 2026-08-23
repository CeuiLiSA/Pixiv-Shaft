package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.loxia.Client
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 「画廊」页（发现 tab · 其他分类入口），feeds 框架的首个线上页面，替代 legacy FragmentWalkThrough。
 *
 * 详情联动 / 收藏同步 / 长按菜单 / ObjectPool 合池 / 标准瀑布流插画卡等通用行为全部在
 * [IllustFeedFragment]；本类只声明数据源（walkthrough 接口 + 内容过滤链，
 * 整页被滤空时由 FeedViewModel 的空页追载继续翻，#729 语义）和 toolbar。
 */
class WalkthroughFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        pixivFeedSource({ Client.appApi.getWalkthroughWorks() }) { resp, _ ->
            resp.displayList.mapNotNull { IllustFeedItem.of(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.walkthrough)
    }
}
