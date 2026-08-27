package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.toUserFeedItems
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 「粉丝」页（feeds 框架版，替代 legacy FragmentWhoFollowThisUser + WhoFollowThisUserRepo + UAdapter）。
 * 关注了某画师的用户列表；TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed），复用
 * [UserFeedFragment] 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步。
 *
 * 端点直接复用 loxia 现成的 [ceui.loxia.API.getUserFans]（/v1/user/follower + user_id），和 legacy
 * repo 打的是同一个接口，翻页同样跟随 next_url（由 [pixivFeedSource] 的默认 nextCursorOf 接管，
 * 一比一换掉 legacy 的 getNextUser(nextUrl)）。唯一差别是 filter=for_ios 而非 legacy 的 for_android
 * ——filter 只影响响应里图片 URL 的形态，用户卡渲染无差别（相关用户页 getRelatedUsers 同理）。
 */
class UserFansFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // 新链路统一传 Long；Params 的兼容读取保留升级前已保存的 Int USER_ID。
    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source 的 lambda
        val userId = userId
        pixivFeedSource(
            initialFetch = { Client.appApi.getUserFans(userId) },
        ) { resp, _ -> resp.user_previews.toUserFeedItems() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_264)
    }

    companion object {
        @JvmStatic
        fun newInstance(userId: Long): UserFansFeedFragment {
            return UserFansFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                }
            }
        }
    }
}
