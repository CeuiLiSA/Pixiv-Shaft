package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.fragments.RecmdUserHandoff
import ceui.lisa.fragments.RecmdUserSnapshot
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.api.model.UserPreviewResponse
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import java.util.concurrent.atomic.AtomicReference

/**
 * 「推荐用户」整页（feeds 框架版，替代 legacy FragmentRecmdUser + RecmdUserRepo(false) + UAdapter）。
 * TemplateActivity 宿主、自带 toolbar，复用 [UserFeedFragment] 的用户卡渲染 / 关注切换 /
 * LIKED_USER 广播同步。同一份数据的横向货架形态见 [RecmdUserRailFeedFragment]。
 *
 * ## 快照交接（[RecmdUserHandoff]）
 * 动态页的推荐货架已经拉过一页 /v1/user/recommended 了，「查看更多」进本页时把那一批 + nextUrl
 * 经 [RecmdUserHandoff] 交接过来（key 走 arguments，多兆的数据本身不进 Intent，见 #820）。省掉一次
 * 重复请求，也保证用户看到的还是刚才那批人——该接口每次调用返回的推荐都不一样，不交接的话
 * 「查看更多」会换成另一批人，货架上刚看中的画师就找不着了。
 *
 * 交接是**把快照当第一页喂给数据源**（[AtomicReference] 一次性消费），而不是
 * `appendItems` + `adoptCursor`：那对 API 服务的是「VM 已经加载过、外部替它续拉了几页再把游标交还」，
 * 而这里的 VM 是全新的、一次都没加载过。走那条路会踩两个坑——[ceui.pixiv.feeds.FeedViewModel.appendItems]
 * 不置 `hasLoadedOnce`，于是 ① `loadMore()` 的 `!hasLoadedOnce` 守卫恒真，列表翻不动第二页；
 * ② `FeedFragment.onResume` 的 `ensureLoaded()` 照样发起 refresh，重复请求没省掉，交接来的数据
 * 还会被网络首屏顶掉。喂进 initialFetch 则一切照常：refresh() 走正常路径立 `hasLoadedOnce`，
 * 快照的 nextUrl 经默认 `nextCursorOf`（取 resp.next_url）成为翻页游标，续读无缝。
 *
 * 一次性消费的另一层意义：下拉刷新时 seed 已被取空，自然落到网络分支拉新的一批——
 * 「刷新」本就该给新内容，不能把交接来的旧快照再摆一遍（对齐 legacy `autoRefresh()` 的语义：
 * 有交接数据就别自动刷，但用户手动刷照常走网络）。
 */
class RecmdUserFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        // 零捕获：先把交接快照取成局部 val 再进 source 的 lambda（consumeHandoff 只在这里读
        // Fragment 的 arguments，AtomicReference 本身不引用 Fragment）
        val seed = AtomicReference(consumeHandoff())
        pixivFeedSource(
            initialFetch = {
                // 一次性：首屏吃快照，之后（下拉刷新）恒走网络
                val seeded = seed.getAndSet(null)
                if (seeded != null) {
                    UserPreviewResponse(seeded.items, seeded.nextUrl)
                } else {
                    Client.appApi.recommendedUsers()
                }
            },
        ) { resp, _ -> mapUsers(resp.displayList) }
    }

    /**
     * 取走货架交接来的快照（消费即移除，避免 [RecmdUserHandoff] 泄漏）。没带 key（从别处直接进本页）、
     * 或进程被杀后重建（map 随进程没了）都返回 null，退化成正常的网络首屏。
     *
     * 只在数据源构造时调用一次：旋转重建复用的是同一个 VM，不会重新走 source 工厂，
     * 因此也不会因为 map 已被消费而把已经在屏幕上的列表弄丢。
     */
    private fun consumeHandoff(): RecmdUserSnapshot? {
        val key = arguments?.getString(ARG_HANDOFF_KEY) ?: return null
        return RecmdUserHandoff.take(key)?.takeIf { it.items.isNotEmpty() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.recomment_user)
    }

    companion object {
        private const val ARG_HANDOFF_KEY = "recmd_user_handoff_key"

        /**
         * 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。
         *
         * 滤掉无 id 的用户：[UserFeedItem.feedKey] 拿 user.id 当身份，null 全部塌成 0L，
         * 框架的 dedupByIdentity 会把它们当同一条只留第一条（DiffUtil 要求身份唯一）。
         * 与货架 [RecmdUserRailFeedFragment.mapUsers] 同源同策略——同一个接口的数据。
         */
        private fun mapUsers(previews: List<UserPreview>): List<FeedItem> {
            return previews.mapNotNull { p ->
                if (p.user?.id == null) null else UserFeedItem(p)
            }
        }

        /**
         * [handoffKey] 为 [RecmdUserHandoff] 里那批货架数据的 key（由动态页「查看更多」放入）；
         * 没有交接数据时传 null，本页自己拉第一页。
         */
        @JvmStatic
        fun newInstance(handoffKey: String?): RecmdUserFeedFragment {
            return RecmdUserFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_HANDOFF_KEY, handoffKey)
                }
            }
        }
    }
}
