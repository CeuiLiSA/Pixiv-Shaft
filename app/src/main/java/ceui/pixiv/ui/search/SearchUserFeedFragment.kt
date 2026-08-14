package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Client
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.toUserFeedItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        SearchUserFeedSource(searchModel)
    }

    override val emptyStateText: CharSequence
        get() = SearchRiskPolicy.withheldQuery(searchModel.keyword.value)?.let { query ->
            getString(R.string.search_results_withheld_notice, query)
        } ?: super.emptyStateText

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

/** 用户搜索的政策门控包装层：拦截判断永远先于 [Client.appApi] 调用。 */
class SearchUserFeedSource(private val searchModel: SearchModel) : FeedSource<String> {

    private var generationSource: PixivFeedSource<UserPreviewResponse>? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        if (cursor != null) {
            return generationSource?.load(cursor) ?: FeedPage(emptyList(), null)
        }

        val keywordSnapshot = searchModel.keyword.value?.trim().orEmpty()
        val shouldWithhold = if (SearchRiskPolicy.isWarmedUp()) {
            SearchRiskPolicy.shouldWithhold(keywordSnapshot)
        } else {
            withContext(Dispatchers.Default) {
                SearchRiskPolicy.shouldWithhold(keywordSnapshot)
            }
        }
        if (shouldWithhold) {
            generationSource = null
            return FeedPage(emptyList(), null)
        }
        val source = pixivFeedSource<UserPreviewResponse>(
            initialFetch = {
                if (keywordSnapshot.isEmpty()) {
                    UserPreviewResponse()
                } else {
                    Client.appApi.searchUser(keywordSnapshot)
                }
            },
        ) { resp, _ -> resp.user_previews.toUserFeedItems() }
        generationSource = source
        return source.load(null)
    }
}
