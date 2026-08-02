package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Client
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.toUserFeedItems

/**
 * 搜索「用户」tab（feeds 框架版，替代 legacy FragmentSearchUser + UAdapter）。复用 [UserFeedFragment]
 * 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步，只加搜索特有逻辑：读 activity-scoped [SearchModel]
 * 最新 keyword 响应式重搜（observe nowGo → keyword 非空就 refresh，一比一换掉 legacy 的
 * repo.update + autoRefresh）。数据源不快照 keyword，见 [searchUserSource]。
 */
class SearchUserFeedFragment : UserFeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获的是 activity-scoped SearchModel（生命周期 ≥ Activity，不是 Fragment），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        pixivFeedSource(
            initialFetch = {
                val word = searchModel.keyword.value?.trim().orEmpty()
                if (word.isEmpty()) UserPreviewResponse() else Client.appApi.searchUser(word)
            },
        ) { resp, _ -> resp.user_previews.toUserFeedItems() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 搜索触发：keyword 非空才重搜（对齐 legacy FragmentSearchUser 的 guard）。
        searchModel.nowGo.observe(viewLifecycleOwner) {
            if (!searchModel.keyword.value.isNullOrBlank()) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchUserFeedFragment = SearchUserFeedFragment()
    }
}
