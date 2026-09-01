package ceui.pixiv.ui.user

import android.content.Intent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.RecyUserPreviewHorizontalBinding
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemHorizontalDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedUserRailSkeletonView
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * 「推荐用户」横向货架（feeds 框架版，替代 legacy FragmentRecmdUserHorizontal + UserHAdapter +
 * RecmdUserRepo(isHorizontal=true)）。宿主是动态页 [ceui.lisa.fragments.FragmentRight] 头部的
 * `user_recmd_fragment` 容器（124dp 高）。
 *
 * 卡片就是 legacy 那张 `recy_user_preview_horizontal`（圆头像 + 名字），点击进画师页。
 * 卡上没有关注按钮，所以不接 LIKED_USER 关注态同步（对齐 legacy UserHAdapter）——也因此不复用
 * [ceui.pixiv.ui.common.UserFeedFragment] 那套竖版用户卡基类，条目自带一份。
 *
 * **只展示第一页**（[loadMoreEnabled] = false，对齐 legacy 的 initNextApi=null +
 * setEnableLoadMore(false)），但数据源照常带回真实 nextUrl —— 宿主「查看更多」要靠
 * [currentSnapshot] 把这一批 + nextUrl 交接给 推荐用户 整页续读。
 */
class RecmdUserRailFeedFragment : FeedFragment() {

    override val loadMoreEnabled: Boolean = false

    // 横向 rail 必须关掉下拉刷新：SwipeRefreshLayout 只在 canChildScrollUp() 为真时才放行手势，
    // 而那条路最终问的是 LayoutManager.canScrollVertically() —— 横向 LinearLayoutManager 恒 false，
    // 于是 SRL 认为「列表滚不动」，把竖向拖拽全认领走。后果:转圈被拖出来画在 124dp 高的推荐条里，
    // 松手还会真的 refresh()，推荐用户在用户手指底下悄悄重排。
    override val refreshEnabled: Boolean = false

    override val feedViewModel by feedViewModels {
        pixivFeedSource({ Client.appApi.recommendedUsers() }) { resp, _ ->
            mapUsers(resp.displayList)
        }
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.setHasFixedSize(true)
        listView.addItemDecoration(LinearItemHorizontalDecoration(DensityUtil.dp2px(12.0f)))
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView {
        return FeedUserRailSkeletonView(requireContext())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(userRailRenderer())
    }

    private fun userRailRenderer() =
        feedRenderer<RecmdUserRailItem, RecyUserPreviewHorizontalBinding>(
            inflate = RecyUserPreviewHorizontalBinding::inflate,
            create = { cell ->
                cell.binding.root.setOnClick {
                    val id = cell.item.preview.user?.id ?: return@setOnClick
                    startActivity(
                        Intent(requireContext(), UActivity::class.java).apply {
                            putExtra(Params.USER_ID, id)
                        },
                    )
                }
            },
            recycle = { cell -> cell.binding.userHead.clearGlideOnRecycle() },
        ) { cell ->
            val user = cell.item.preview.user
            cell.binding.userName.text = user?.name ?: ""
            Glide.with(cell.binding.userHead)
                .load(GlideUtil.getUrl(user?.profile_image_urls?.medium))
                .placeholder(R.color.light_bg)
                .error(R.drawable.no_profile)
                .into(cell.binding.userHead)
        }

    /**
     * 交接给「查看更多」([RecmdUserFeedFragment] 整页) 的快照：当前这一批 + 续读游标。
     *
     * 直接给 [UserPreview]：整页也是 feeds 版、要的就是这个类型。以前这里 gson 转成 legacy
     * `UserPreviewsBean` 是为了迁就 legacy 消费方 FragmentRecmdUser，那边下线后转换只剩浪费
     * （对面还得再转回来）。少一层转换也就没有「转不动的条目」要兜了。
     */
    fun currentSnapshot(): Pair<List<UserPreview>, String?> {
        val previews = feedViewModel.uiState.value.items
            .filterIsInstance<RecmdUserRailItem>()
            .map { it.preview }
        return previews to feedViewModel.currentCursor
    }

    companion object {
        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapUsers(previews: List<UserPreview>): List<FeedItem> {
            return previews.mapNotNull { p ->
                if (p.user?.id == null) null else RecmdUserRailItem(p)
            }
        }
    }
}

/**
 * 推荐用户货架条目：只持不可变的 [UserPreview]。
 *
 * 货架卡没有关注按钮、也不参与关注态回流，所以不用
 * [ceui.pixiv.ui.common.UserFeedItem] 那套（那份带 withFollowed / 预览图）；这里只要 id + 头像 + 名字。
 */
class RecmdUserRailItem(val preview: UserPreview) : FeedItem {

    override val feedKey: Any get() = preview.user?.id ?: 0L

    override fun equals(other: Any?): Boolean {
        return other is RecmdUserRailItem && other.preview == preview
    }

    override fun hashCode(): Int = preview.hashCode()
}
