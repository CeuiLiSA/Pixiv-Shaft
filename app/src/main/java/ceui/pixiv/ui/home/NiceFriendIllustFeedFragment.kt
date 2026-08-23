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
 * 「好P友的插画/漫画作品」页（侧边栏 · 我的），feeds 框架版，替代 legacy FragmentNiceFriendIllust。
 *
 * 数据是 mypixiv(互关好友)的作品流，无参数、纯 nextUrl 翻页。详情联动 / 收藏同步 / 长按菜单 /
 * ObjectPool 合池 / 标准瀑布流插画卡等通用行为全部在 [IllustFeedFragment]；本类只声明数据源
 * （mypixiv 接口 + [IllustFeedItem.from] 的内容过滤链，整页被滤空时由 FeedViewModel 空页追载
 * 继续翻，#729 语义）和 toolbar。
 */
class NiceFriendIllustFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        pixivFeedSource({ Client.appApi.getNiceFriendIllust() }) { resp, _ ->
            resp.displayList.mapNotNull { IllustFeedItem.of(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.string_274)
    }
}
