package ceui.pixiv.ui.discovery

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
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
        get() = if (SessionManager.hasWebCookie) super.emptyStateText
        else getString(R.string.web_discovery_login_needed)

    override val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() = if (SessionManager.hasWebCookie) null else {
            getString(R.string.street_web_login_confirm) to {
                (requireParentFragment() as WebDiscoveryFragment).openWebLogin()
            }
        }

    private var needsSessionRefresh = false

    fun onWebLoginReturned() {
        needsSessionRefresh = true
        if (isResumed) refreshSession()
    }

    override fun onResume() {
        // 在基类 ensureStarted 前消费会话更新，首次登录回来只请求一次。
        if (needsSessionRefresh) refreshSession()
        super.onResume()
    }

    private fun refreshSession() {
        needsSessionRefresh = false
        // 登录可能切换了网页账号，清除上一会话内容后再拉取。
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
