package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.pixiv.api.Client
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.toUserFeedItems
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 「好P友」页（feeds 框架版，替代 legacy FragmentNiceFriend + NiceFriendRepo + UAdapter）。
 *
 * mypixiv = pixiv 的互关好友。用户卡渲染 / 关注切换（含长按私密关注）/ LIKED_USER 广播跨列表
 * 同步 / 点击进画师页全部继承 [UserFeedFragment]；本类只声明数据源和 toolbar。
 *
 * 端点与 legacy 一致：`/v1/user/mypixiv?filter=for_android`（
 * [ceui.pixiv.api.API.getUserPixivFriends]），翻页走响应自带的 nextUrl。
 * 注意 `filter=for_android` 是迁移时补上的——那个声明此前零调用方、一直漏着 filter，
 * 而 legacy `AppApi.getNiceFriend` 一直带着它（见该声明处的注释）。
 *
 * 3 张预览图不足留空、不拿小说封面补位——见 [UserFeedFragment] 的裁决说明。
 */
class NiceFriendFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // 新链路统一传 Long；Params 的兼容读取保留升级前已保存的 Int USER_ID。
    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source 的 lambda
        val userId = userId
        pixivFeedSource({ Client.appApi.getUserPixivFriends(userId) }) { resp, _ ->
            resp.user_previews.toUserFeedItems()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_235)
    }

    companion object {
        @JvmStatic
        fun newInstance(userId: Long): NiceFriendFeedFragment {
            return NiceFriendFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                }
            }
        }
    }
}
