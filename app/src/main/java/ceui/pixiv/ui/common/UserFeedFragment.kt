package ceui.pixiv.ui.common

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

/** 关注态局部重绑的 payload 标记（按引用识别）。 */
private val PAYLOAD_USER_FOLLOW = Any()

/**
 * 用户列表 feed 基类（对齐插画侧 [IllustFeedFragment]、小说侧 NovelFeedFragment）。持 recy_user_preview
 * 渲染器 + 关注切换（乐观翻态 + 长按私密关注）+ LIKED_USER 广播跨列表同步 + 点击进画师页。
 * 子类只需提供 [feedViewModel]（数据源），并可传自定义布局（如带 toolbar 的 fragment_toolbar_feed）。
 *
 * 现有实现：搜索用户 [ceui.pixiv.ui.search.SearchUserFeedFragment]、相关用户
 * [ceui.pixiv.ui.user.RelatedUserFeedFragment]、正在关注 [ceui.pixiv.ui.user.FollowUserFeedFragment]、
 * 粉丝 [ceui.pixiv.ui.user.UserFansFeedFragment]、推荐用户 [ceui.pixiv.ui.user.RecmdUserFeedFragment]、
 * 画师榜 [ceui.pixiv.ui.recommend.ArtistRankFeedFragment]。
 *
 * **3 张预览图默认只显插画，不足留空**。legacy `UAdapter` 在插画不足 3 张时会拿小说封面补位；
 * 关注列表里小说家密度高、空三格观感差，所以由 [ceui.pixiv.ui.user.FollowUserFeedFragment]
 * 打开 [fillPreviewWithNovelCovers] 恢复补位，其余用户列表页维持「不足留空」。
 * 补位需要封面 URL，[ceui.loxia.UserPreview.novels] 相应解析为 `List<Novel>`。
 *
 * 卡片布局与交互语义源自 legacy `UAdapter`（迁移时逐条对齐）。**该类已随最后一个调用方一起
 * 删除**，要考古去 git 历史，别在工作区找。
 */
abstract class UserFeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : FeedFragment(contentLayoutId) {

    /**
     * 插画不足 3 张时是否用小说封面补位（对齐 legacy UAdapter 的行为）。
     * 默认关：搜索/相关/粉丝等用户列表页保持「不足留空」；
     * 关注列表由 FollowUserFeedFragment 打开，因为那里小说家密度高、空三格观感差。
     */
    protected open val fillPreviewWithNovelCovers: Boolean = false

    /**
     * 头像 + 3 张预览图的 Glide 请求管理器，建一次复用（对齐插画侧 [IllustFeedFragment.illustGlide]）。
     * 一张用户卡 recycle 时要清 4 个 ImageView，bind 时要加载 4 张图——每处都 `Glide.with(view)`
     * 就是 8 次递归遍历 fragment 树找承载 fragment，全在 fling 帧路径上。`Glide.with(Fragment)`
     * 直接命中、无查找，解析出的是同一个 RequestManager，行为等价。
     */
    private val userGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(userGlide)
        // 关注态跨列表同步：其它页/本页关注某用户 → LIKED_USER 广播回流
        feedLikeSync<UserFeedItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_USER,
            idOf = { it.user?.id },
            transform = { item, followed -> item.withFollowed(followed) },
        ).bind(requireContext(), viewLifecycleOwner)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(userRenderer())
    }

    private fun userRenderer() = feedRenderer<UserFeedItem, RecyUserPreviewBinding>(
        inflate = RecyUserPreviewBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { cell.item.user?.id?.let { openUserActivity(it) } }
            cell.binding.postLikeUser.setOnClick { toggleFollow(cell) }
            cell.binding.postLikeUser.setOnLongClickListener {
                privateFollow(cell)
                true
            }
        },
        recycle = { cell ->
            userGlide.clear(cell.binding.userHead)
            listOf(cell.binding.userShowOne, cell.binding.userShowTwo, cell.binding.userShowThree)
                .forEach { userGlide.clear(it) }
        },
        changePayload = { old, new ->
            // 只有关注态变 → 局部重绑关注按钮，不重跑 3 张预览图 + 头像 Glide（对齐插画/小说卡）。
            val ou = old.user
            val nu = new.user
            if (ou != null && nu != null &&
                old.preview.copy(user = ou.copy(is_followed = nu.is_followed)) == new.preview
            ) PAYLOAD_USER_FOLLOW else null
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_USER_FOLLOW }) {
                renderFollow(cell.binding, cell.item.user?.is_followed == true)
                true
            } else {
                false
            }
        },
    ) { cell -> bindUser(cell) }

    private fun bindUser(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val b = cell.binding
        val preview = cell.item.preview
        val user = preview.user
        val ctx = b.root.context

        // 3 张方形预览图，边长 = 屏宽/3。默认只显示插画预览（不足留空，见类文档）。
        val size = ctx.resources.displayMetrics.widthPixels / 3
        val slots = listOf(b.userShowOne, b.userShowTwo, b.userShowThree)
        slots.forEach { iv ->
            // 尺寸没变就别赋值：setLayoutParams 无条件触发 requestLayout，而这里是 fling 帧路径上
            // 每张卡跑 3 次。屏宽只在旋转/分屏时变，那时 holder 本来就会重新走一遍 bind。
            val lp = iv.layoutParams
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                iv.layoutParams = lp
            }
        }
        val illusts = preview.illusts
        // 关注列表打开 fillPreviewWithNovelCovers 时，插画不足的位置用小说封面补位
        //（realCoverUrl 跳过占位图，与作者页 banner 同口径 —— 补位铺一张灰底还不如留空）。
        val novelCovers = if (fillPreviewWithNovelCovers) {
            preview.novels.orEmpty().mapNotNull { it.realCoverUrl }
        } else {
            emptyList()
        }
        slots.forEachIndexed { i, iv ->
            val url = illusts.getOrNull(i)?.image_urls?.let { it.square_medium ?: it.medium }
                ?: novelCovers.getOrNull(i - illusts.size)
            userGlide.load(GlideUtil.getUrl(url)).placeholder(R.color.light_bg).into(iv)
        }

        b.userName.text = user?.name ?: ""
        userGlide.load(GlideUtil.getUrl(user?.profile_image_urls?.medium))
            .error(R.drawable.no_profile).into(b.userHead)
        renderFollow(b, user?.is_followed == true)
    }

    /** 关注按钮文案：已关注 / 关注。 */
    private fun renderFollow(b: RecyUserPreviewBinding, followed: Boolean) {
        b.postLikeUser.text = getString(if (followed) R.string.post_unfollow else R.string.post_follow)
    }

    /** VM 里当前这个用户的最新条目（真源）；已被刷新挤掉则 null。 */
    private fun currentUserItem(userId: Long): UserFeedItem? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is UserFeedItem && it.user?.id == userId } as? UserFeedItem
    }

    private fun toggleFollow(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        // 关注态的真源是 VM 的当前状态，不是 cell.item —— 后者是 adapter **已提交的快照**，要等
        // ListAdapter 后台 diff 落地才换新。读 cell.item 的后果：连点两下时第二下仍看到上一下之前
        // 的旧态，把「取消关注」反转成「再关注一次」，取消这个操作直接丢失（与插画/小说卡同一 bug 类）。
        val tapped = cell.itemOrNull?.user ?: return
        val userId = tapped.id
        val user = currentUserItem(userId)?.user ?: tapped
        val target = user.is_followed != true
        renderFollow(cell.binding, target) // 当帧翻文案（异步 updateItems 落地兜底）
        applyFollow(userId, target)
        // 复用 legacy follow op：它现在是 PixivActions 的薄封装，本地态 + LIKED_USER 广播当帧
        // 生效，请求进 PixivActionQueue 限流后发。失败回滚也由队列统一做：它会带相反的值再发
        // 一次 LIKED_USER，本页 onViewCreated 挂的 FeedLikeSync 收到就把条目拨回去（幂等）。
        // 不再用回调式回滚——队列可能在冷却几分钟后才判定失败，那时本 Fragment 早销毁了。
        if (target) {
            PixivOperate.postFollowUser(userId.toInt(), PixivActions.defaultFollowRestrict())
        } else {
            PixivOperate.postUnFollowUser(userId.toInt())
        }
    }

    /** 长按 = 私密关注（沿用 legacy 的长按语义）。 */
    private fun privateFollow(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val user = cell.itemOrNull?.user ?: return
        val userId = user.id
        // 长按恒为「私密关注」（目标态固定 true），所以不必像 toggleFollow 那样回 VM 读当前态；
        // 失败回滚同 toggleFollow，由队列的 LIKED_USER 广播完成。
        renderFollow(cell.binding, true)
        applyFollow(userId, true)
        PixivOperate.postFollowUser(userId.toInt(), Params.TYPE_PRIVATE)
    }

    private fun applyFollow(userId: Long, followed: Boolean) {
        applyFollow(feedViewModel, userId, followed)
    }

    private fun applyFollow(viewModel: FeedViewModel<*>, userId: Long, followed: Boolean) {
        viewModel.updateItems(UserFeedItem::class.java) { item ->
            if (item.user?.id == userId) item.withFollowed(followed) else item
        }
    }
}

/**
 * [UserPreview] 列表 → [UserFeedItem] 列表：**丢掉 user 为 null 的预览**。
 *
 * [UserFeedItem.feedKey] 取 `user?.id ?: 0L`，多条 user 为 null 的预览会全部塌成同一个身份 0L，
 * 被框架的 `dedupByIdentity` 静默丢弃到只剩一条；而且这种条目连头像、名字、点击进画师页都没有，
 * 本来就没有展示价值。与其让去重兜底，不如在映射处就滤掉（[ceui.pixiv.ui.user.RecmdUserFeedFragment]
 * 的 mapUsers 早就是这么做的，此处让其余用户列表对齐）。
 */
fun List<UserPreview>.toUserFeedItems(): List<UserFeedItem> =
    filter { it.user != null }.map(::UserFeedItem)

/**
 * 用户 feed 条目：持 loxia [UserPreview]（含 [User] + 预览插画）。feeds 框架的用户列表基建
 * （对齐插画侧 [IllustFeedItem] / 小说侧 NovelFeedItem）。
 *
 * 内容相等性看整个 [UserPreview]（data class 深比较）：关注态（user.is_followed）或预览图变了
 * 都重绑。关注乐观切态走 [withFollowed]。
 */
class UserFeedItem(val preview: UserPreview) : FeedItem {

    val user: User? get() = preview.user

    override val feedKey: Any get() = preview.user?.id ?: 0L

    override fun equals(other: Any?): Boolean {
        return other is UserFeedItem && other.preview == preview
    }

    override fun hashCode(): Int = preview.hashCode()

    /** 关注态变更：copy 出新实例驱动 DiffUtil 重绑关注按钮。user 为 null 时原样返回。 */
    fun withFollowed(followed: Boolean): UserFeedItem {
        val u = preview.user ?: return this
        if (u.is_followed == followed) return this
        return UserFeedItem(preview.copy(user = u.copy(is_followed = followed)))
    }
}
