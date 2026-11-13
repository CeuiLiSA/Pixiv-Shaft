package ceui.pixiv.ui.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.User
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.playToggleHaptic
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.cardSurface
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pressScale
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.NoTransition
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.request.transition.TransitionFactory

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
 * **3 张预览图：插画不足 3 张时用小说封面补位**（对齐 legacy `UAdapter`，补位需要封面 URL，
 * [ceui.pixiv.api.model.UserPreview.novels] 相应解析为 `List<Novel>`）。补位一度只对关注列表开、其余页
 * 「不足留空」，但纯小说作者在哪张列表里都是空三格 —— 相关用户 / 粉丝 / 搜索用户页照样难看
 * （issue #997），而「留空」这个默认从来没有过理由，所以开关删掉、全部页面一致补位。
 *
 * 卡片布局与交互语义源自 legacy `UAdapter`（迁移时逐条对齐）。**该类已随最后一个调用方一起
 * 删除**，要考古去 git 历史，别在工作区找。
 *
 * **V3 卡片（2026-11-04）**：长相对齐 V3 画师主页 —— 22dp `cardFill` 卡 + hairline、头像主题色描边、
 * Montserrat 名字 / 账号、主题实色「关注」与浅色「已关注 / 悄悄关注中」胶囊；列表左右 16dp、卡间 12dp、
 * 宽屏收到 720dp 居中，首屏画 [UserCardSkeletonView]。几何改动要同步改骨架，见 `recy_user_preview.xml`。
 */
abstract class UserFeedFragment(
    @LayoutRes contentLayoutId: Int = ceui.pixiv.feeds.R.layout.fragment_feed,
    private val fadePreviewImagesOnLoad: Boolean = false,
) : FeedFragment(contentLayoutId) {

    /**
     * 头像 + 3 张预览图的 Glide 请求管理器，建一次复用（对齐插画侧 [IllustFeedFragment.illustGlide]）。
     * 一张用户卡 recycle 时要清 4 个 ImageView，bind 时要加载 4 张图——每处都 `Glide.with(view)`
     * 就是 8 次递归遍历 fragment 树找承载 fragment，全在 fling 帧路径上。`Glide.with(Fragment)`
     * 直接命中、无查找，解析出的是同一个 RequestManager，行为等价。
     */
    private val userGlide: RequestManager by lazy { Glide.with(this) }
    private val previewImageTransition by lazy(LazyThreadSafetyMode.NONE) {
        DrawableTransitionOptions().transition(UserPreviewTransitionFactory(requireContext()))
    }

    /**
     * 卡底 / 胶囊 / 文字色都从宿主主色派生。换主题会重建 Activity 连同本 Fragment，
     * 所以按实例缓存一份即可，不必每次 bind 重算。
     */
    private val palette: V3Palette by lazy { V3Palette.from(requireContext()) }

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
        // 「已关注 ↔ 悄悄关注中」只改可见性、不改 is_followed：条目内容没变，DiffUtil 不会重绑，
        // 得自己按 id 把屏幕上那张卡的胶囊文案刷一下（画师主页长按切换后返回本页就是这条路径）。
        FollowVisibility.changes.observe(viewLifecycleOwner) { userId ->
            refreshFollowPill(userId)
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(UserCardDecoration(requireContext()))
        // 分屏 / 自由窗口改宽度时不一定重建，偏移要跟着新宽度重算一次。首次布局（旧宽 0）不算：
        // 那一遍 getItemOffsets 读到的已经是真实宽度，再 invalidate 只会白跑一整遍列表布局。
        listView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            if (oldWidth != 0 && right - left != oldWidth) (v as RecyclerView).invalidateItemDecorations()
        }
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView =
        UserCardSkeletonView(requireContext())

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(userRenderer())
    }

    private fun userRenderer() = feedRenderer<UserFeedItem, RecyUserPreviewBinding>(
        inflate = RecyUserPreviewBinding::inflate,
        create = { cell ->
            val b = cell.binding
            // 22dp 卡片：主题派生的卡底 + hairline，按下是同形状的主题色涟漪；顶部三张图随轮廓裁圆角。
            b.root.background = b.root.context.cardSurface(22)
            b.root.clipToOutline = true
            b.root.setOnClickListener { cell.itemOrNull?.user?.id?.let { openUserActivity(it) } }
            b.postLikeUser.pressScale()
            b.postLikeUser.setOnClickListener { toggleFollow(cell) }
            b.postLikeUser.setOnLongClickListener {
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
                val user = cell.item.user
                renderFollow(cell.binding, user?.id ?: 0L, user?.is_followed == true)
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

        // 3 张 1:1 预览图，边长由布局按卡宽等分（ConstraintLayout 比例），这里只管填图。
        val slots = listOf(b.userShowOne, b.userShowTwo, b.userShowThree)
        val illusts = preview.illusts
        // 插画不足的位置用小说封面补位（realCoverUrl 跳过占位图，与作者页 banner 同口径 ——
        // 补位铺一张灰底还不如留空）。三格已被插画占满就别遍历 novels：这是 fling 帧路径，
        // 而插画作者（绝大多数卡）走的正是这条短路。
        val novelCovers = if (illusts.size >= slots.size) {
            emptyList()
        } else {
            preview.novels.orEmpty().mapNotNull { it.realCoverUrl }
        }
        slots.forEachIndexed { i, iv ->
            val url = illusts.getOrNull(i)?.image_urls?.let { it.square_medium ?: it.medium }
                ?: novelCovers.getOrNull(i - illusts.size)
            // 占位 / 补不上的空格是中性浅面（日夜各一档），不再是夜间也亮着的 #f2f2f2。
            val request = userGlide.load(GlideUtil.getUrl(url))
                .placeholder(R.color.v3_surface_2)
            // 过渡工厂在图片就绪时检查系统动画开关，并让内存缓存命中直接显示。
            val imageRequest = if (fadePreviewImagesOnLoad) {
                request.transition(previewImageTransition)
            } else {
                request
            }
            imageRequest.into(iv)
        }

        b.userName.text = user?.name ?: ""
        val account = user?.account
        b.userAccount.isVisible = !account.isNullOrBlank()
        if (b.userAccount.isVisible) b.userAccount.text = "@$account" // 同画师主页 / 作品页作者栏
        userGlide.load(GlideUtil.getUrl(user?.profile_image_urls?.medium))
            .error(R.drawable.no_profile).into(b.userHead)
        renderFollow(b, user?.id ?: 0L, user?.is_followed == true)
    }

    /** 关注胶囊（与小说详情作者行共用，见 [renderFollowPill]）。 */
    private fun renderFollow(b: RecyUserPreviewBinding, userId: Long, followed: Boolean) {
        b.postLikeUser.renderFollowPill(palette, userId, followed)
    }

    /** 屏幕上这位用户的卡（若在）按 VM 当前关注态重画胶囊；只改了可见性时用。 */
    private fun refreshFollowPill(userId: Long) {
        val list = view?.let { feedBinding.feedListView } ?: return
        for (i in 0 until list.childCount) {
            val cell = list.getChildViewHolder(list.getChildAt(i)) as? FeedCell<*, *> ?: continue
            val b = cell.binding as? RecyUserPreviewBinding ?: continue
            val item = cell.itemOrNull as? UserFeedItem ?: continue
            if (item.user?.id != userId) continue
            val followed = (currentUserItem(userId) ?: item).user?.is_followed == true
            renderFollow(b, userId, followed)
        }
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
        playToggleHaptic(cell.binding.postLikeUser, target)
        applyFollow(userId, target)
        // 复用 legacy follow op：它现在是 PixivActions 的薄封装，本地态 + LIKED_USER 广播当帧
        // 生效，请求进 PixivActionQueue 限流后发。失败回滚也由队列统一做：它会带相反的值再发
        // 一次 LIKED_USER，本页 onViewCreated 挂的 FeedLikeSync 收到就把条目拨回去（幂等）。
        // 不再用回调式回滚——队列可能在冷却几分钟后才判定失败，那时本 Fragment 早销毁了。
        if (target) {
            PixivOperate.postFollowUser(userId, PixivActions.defaultFollowRestrict())
        } else {
            PixivOperate.postUnFollowUser(userId)
        }
        // 当帧翻胶囊（异步 updateItems 落地兜底）。放在发请求之后：那一步先写可见性，
        // 「默认悄悄关注」开着时这里才能直接读出「悄悄关注中」。
        renderFollow(cell.binding, userId, target)
    }

    /** 长按 = 私密关注（沿用 legacy 的长按语义）。 */
    private fun privateFollow(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val user = cell.itemOrNull?.user ?: return
        val userId = user.id
        // 长按恒为「私密关注」（目标态固定 true），所以不必像 toggleFollow 那样回 VM 读当前态；
        // 失败回滚同 toggleFollow，由队列的 LIKED_USER 广播完成。
        applyFollow(userId, true)
        PixivOperate.postFollowUser(userId, Params.TYPE_PRIVATE)
        renderFollow(cell.binding, userId, true) // 同 toggleFollow：可见性已写入，直接出「悄悄关注中」
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

/** 手动计时的 TransitionDrawable 不跟随系统动画开关，需在每次图片就绪时现读。 */
internal class PreviewImageTransitionFactory(
    context: Context,
    durationMs: Int? = null,
) : TransitionFactory<Drawable> {
    private val contentResolver = context.applicationContext.contentResolver
    private val crossFade = if (durationMs != null) {
        DrawableCrossFadeFactory.Builder(durationMs).build()
    } else {
        DrawableCrossFadeFactory.Builder().build()
    }

    override fun build(dataSource: DataSource, isFirstResource: Boolean): Transition<Drawable> {
        val enabled = if (Build.VERSION.SDK_INT >= 26) {
            motionEnabled()
        } else {
            // motionEnabled() 在 API 24/25 恒为 true；沿用 FeedSkeletonView 的读取方式。
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
        return if (enabled) crossFade.build(dataSource, isFirstResource) else NoTransition.get()
    }
}

internal typealias UserPreviewTransitionFactory = PreviewImageTransitionFactory

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
 * 用户 feed 条目：持 [UserPreview]（含 [User] + 预览插画）。feeds 框架的用户列表基建
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
