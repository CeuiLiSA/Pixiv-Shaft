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
 * 「相关用户」页（feeds 框架版，替代 legacy FragmentRelatedUser + RelatedUserRepo + UAdapter）。
 * 某画师的相关画师推荐；TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed），复用
 * [UserFeedFragment] 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步。
 */
class RelatedUserFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // 新链路统一传 Long；Params 的兼容读取保留升级前已保存的 Int USER_ID。
    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source 的 lambda
        val userId = userId
        pixivFeedSource(
            initialFetch = { Client.appApi.getRelatedUsers(userId) },
        ) { resp, _ -> resp.user_previews.toUserFeedItems() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_436)
    }

    companion object {
        @JvmStatic
        fun newInstance(userId: Long): RelatedUserFeedFragment {
            return RelatedUserFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                }
            }
        }
    }
}
