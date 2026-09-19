package ceui.pixiv.ui.discovery

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem

/** 官网发现的一个分级页面；每个筛选保留自己的列表、游标和滚动位置。 */
class WebDiscoveryFeedFragment : IllustFeedFragment() {
    private val mode by lazy(LazyThreadSafetyMode.NONE) {
        WebDiscoveryMode.valueOf(requireArguments().getString(ARG_MODE)!!)
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        WebDiscoverySource(Client.webApi, mode)
    }

    override val applyBottomSafeInset: Boolean = true
    override val detailContinuationCursor: String? get() = null
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun feedItemFromBean(bean: Illust?): IllustFeedItem? =
        bean?.takeIf { mode.accepts(it.x_restrict ?: 0) }
            ?.let { IllustFeedItem.of(it, skipR18Filter = true) }

    override val emptyStateText: CharSequence
        get() = if (WebDiscoverySession.isCurrentAccount) super.emptyStateText
        else getString(R.string.web_discovery_login_needed)

    override val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() = if (WebDiscoverySession.isCurrentAccount) null else {
            getString(R.string.street_web_login_confirm) to {
                (requireParentFragment() as WebDiscoveryFragment).openWebLogin()
            }
        }

    fun onWebLoginReturned() {
        val state = feedViewModel.uiState.value
        if (!state.hasLoadedOnce && state.refresh is LoadState.Idle) return
        // 已访问的页立即在 VM 中换代，取消旧请求；不能把刷新留在会因重建丢失的 Fragment 标记里。
        // 未访问的页仍由基类首次 RESUMED 时加载。
        feedViewModel.mutateItems { emptyList() }
        feedViewModel.refresh()
    }

    companion object {
        private const val ARG_MODE = "web_discovery_mode"
        fun newInstance(mode: WebDiscoveryMode) = WebDiscoveryFeedFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
