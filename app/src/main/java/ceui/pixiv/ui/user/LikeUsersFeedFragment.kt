package ceui.pixiv.ui.user

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.databinding.RecySimpleUserBinding
import ceui.lisa.model.ListSimpleUser
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.lisa.repo.NovelBookmarkUserRepo
import ceui.lisa.repo.SimpleUserRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「喜欢这个作品的用户」列表页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentListSimpleUser] +
 * SimpleUserAdapter(非 muted)）。
 *
 * TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed）。数据源直接包裹既有的
 * [SimpleUserRepo]（getUsersWhoLikeThisIllust + getNextSimpleUser），把 Rx→suspend 桥一下即可，
 * 翻页跟随 next_url。
 *
 * **row 复刻 muted 那套 recy_simple_user，但去掉 muted 专属交互**：这里是普通的「点赞用户」列表，
 * item 长按不再是解除屏蔽（非 muted 模式下 SimpleUserAdapter 的 item 长按本就是 no-op），只保留
 * item 点按进画师页 + 关注按钮点按切换 / 长按私密关注（对齐 legacy 非 muted 分支）。
 *
 * **不复用 [ceui.pixiv.ui.common.UserFeedFragment]**（同 [ceui.pixiv.ui.muted.MutedUserFeedFragment]
 * 的理由）：它渲染的是 recy_user_preview（头像 + 3 张预览插画）、条目是 loxia UserPreview，而本页要的
 * 是 legacy recy_simple_user（头像 + 名字 + 关注按钮）+ legacy [UserBean]，卡形与数据模型都对不上。
 *
 * **这里是新鲜网络用户**（不同于 muted 存的冻结 JSON）：关注 / 池行为照常，无 poolableBeansOf /
 * 陈旧 bean 喂池的顾虑；关注切换仍是用户显式点击触发的网络写入。
 */
class LikeUsersFeedFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 arg 读进局部 val，只把 illustId(Int) 传进 source（source 归 VM 长期持有，
        // 绝不能捕获 Fragment）。
        val args = requireArguments()
        val novelId = args.getLong(Params.NOVEL_ID, 0L).takeIf { it != 0L }
        val illust = args.getSerializable(Params.CONTENT) as? IllustsBean
        when {
            novelId != null -> LikeUsersFeedSource(novelId)
            illust != null -> LikeUsersFeedSource(illust.id)
            else -> error("LikeUsersFeedFragment 缺少作品参数")
        }
    }

    /**
     * 头像 Glide 请求管理器，建一次复用（对齐 [ceui.pixiv.ui.muted.MutedUserFeedFragment.userGlide]）：
     * bind 加载 / recycle 清理都走它，避免每处 `Glide.with(view)` 递归找承载 fragment。
     */
    private val userGlide: RequestManager by lazy { Glide.with(this) }

    /**
     * 其他页（如画师页）关注 / 取关本列表里的用户 → 同步按钮态。对齐 legacy：NetListFragment 曾为
     * SimpleUserAdapter 注册 LIKED_USER 的 CommonReceiver，feeds 版自己补一个。
     * PixivOperate.postFollowUser/postUnFollowUser 成功后广播 LIKED_USER（ID + IS_LIKED）。
     */
    private val followSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getIntExtra(Params.ID, 0) ?: return
            if (id == 0) return
            val liked = intent.getBooleanExtra(Params.IS_LIKED, false)
            applyFollowed(feedViewModel, id, liked)
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(likeUserRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐 legacy ListFragment 的 LinearItemDecoration(12dp)。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(userGlide)
        setUpToolbar(binding, feedBinding.feedListView)
        // 标题读局部 val（零捕获），复刻 legacy getToolbarTitle。
        val args = requireArguments()
        val novelId = args.getLong(Params.NOVEL_ID, 0L).takeIf { it != 0L }
        val title = args.getString(Params.TITLE)
            ?: (args.getSerializable(Params.CONTENT) as? IllustsBean)?.title
        binding.toolbarTitle.text = "喜欢" + title + "的用户"

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(followSyncReceiver, IntentFilter(Params.LIKED_USER))
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(followSyncReceiver)
        super.onDestroyView()
    }

    private fun likeUserRenderer() = feedRenderer<LikeUserFeedItem, RecySimpleUserBinding>(
        inflate = RecySimpleUserBinding::inflate,
        create = { cell ->
            // 点 item → 进画师页（非 muted 模式 item 无长按语义，故不设长按监听）。
            cell.binding.root.setOnClick { openProfile(cell.item.user) }
            // 点按钮 → 关注 / 取关切换；长按按钮 → 私密关注（沿用 legacy 非 muted 分支）。
            cell.binding.postLikeUser.setOnClick { toggleFollow(cell) }
            cell.binding.postLikeUser.setOnLongClickListener {
                privateFollow(cell)
                true
            }
        },
        recycle = { cell -> userGlide.clear(cell.binding.userHead) },
    ) { cell -> bindRow(cell) }

    private fun bindRow(cell: FeedCell<LikeUserFeedItem, RecySimpleUserBinding>) {
        val b = cell.binding
        val item = cell.item
        val user = item.user
        b.userName.text = user.name
        userGlide.load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(b.userHead)
        // 读条目上的不可变快照，不读可变 bean：bean 是新旧两代条目共享的同一个实例，
        // 拿它渲染的话「关注态变了」这件事在 DiffUtil 眼里根本不存在（见 [LikeUserFeedItem]）。
        renderFollow(b, item.followed)
    }

    /** 关注按钮文案：已关注 / 关注。 */
    private fun renderFollow(b: RecySimpleUserBinding, followed: Boolean) {
        b.postLikeUser.text =
            getString(if (followed) R.string.post_unfollow else R.string.post_follow)
    }

    private fun openProfile(user: UserBean) {
        Common.showUser(requireContext(), user)
    }

    /** VM 里这个用户当前的关注态（真源，adapter 快照可能还没跟上）。 */
    private fun currentFollowed(userId: Int): Boolean? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is LikeUserFeedItem && it.user.id == userId }
            ?.let { (it as LikeUserFeedItem).followed }
    }

    /**
     * 关注态落地：先把可变 bean 校准到目标态，再换条目实例驱动 DiffUtil 重绑。
     *
     * bean 的就地修改**必须留在 transform 外**：[ceui.pixiv.feeds.FeedViewModel.mutateItems]
     * 的 edit 契约是纯函数（它可能被 StateFlow 重放），而且新旧两代条目共享同一个 bean 实例，
     * 在 transform 里改它等于把 DiffUtil 的 oldItem 内容一起改掉。
     */
    private fun applyFollowed(viewModel: FeedViewModel<String>, userId: Int, followed: Boolean) {
        viewModel.uiState.value.items.forEach { item ->
            if (item is LikeUserFeedItem && item.user.id == userId) {
                item.user.isIs_followed = followed
            }
        }
        viewModel.updateItems(LikeUserFeedItem::class.java) { item ->
            if (item.user.id == userId) item.withFollowed(followed) else item
        }
    }

    /** 关注 / 取关切换：乐观翻按钮文案 + 落地条目状态，失败回滚。 */
    private fun toggleFollow(cell: FeedCell<LikeUserFeedItem, RecySimpleUserBinding>) {
        // 真源是 VM 当前状态而非 cell.item（adapter 已提交的快照）：连点两下时读快照会把
        // 「取关」反转成「再关注一次」。与插画 / 小说 / 用户卡同一 bug 类。
        val user = cell.itemOrNull?.user ?: return
        val target = !(currentFollowed(user.id) ?: user.isIs_followed)
        renderFollow(cell.binding, target)
        applyFollowed(feedViewModel, user.id, target)
        // 失败回滚由 PixivActionQueue 统一做：它会带相反的值再发一次 LIKED_USER，
        // 本页的 followSyncReceiver 收到就把条目拨回去（applyFollowed 幂等）。
        if (target) {
            PixivOperate.postFollowUser(user.id, Params.TYPE_PUBLIC)
        } else {
            PixivOperate.postUnFollowUser(user.id)
        }
    }

    /** 长按按钮 = 私密关注（沿用 legacy 的长按语义）。目标态恒为「已关注」，失败由队列广播回滚。 */
    private fun privateFollow(cell: FeedCell<LikeUserFeedItem, RecySimpleUserBinding>) {
        val user = cell.itemOrNull?.user ?: return
        renderFollow(cell.binding, true)
        applyFollowed(feedViewModel, user.id, true)
        PixivOperate.postFollowUser(user.id, Params.TYPE_PRIVATE)
    }

    companion object {
        @JvmStatic
        fun newInstance(illust: IllustsBean): LikeUsersFeedFragment {
            return LikeUsersFeedFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(Params.CONTENT, illust)
                }
            }
        }

        @JvmStatic
        fun newInstance(novelId: Long, novelTitle: String?): LikeUsersFeedFragment {
            return LikeUsersFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.NOVEL_ID, novelId)
                    putString(Params.TITLE, novelTitle)
                }
            }
        }
    }
}

/**
 * 点赞用户条目：持 legacy [UserBean]。
 * feedKey 用画师 id（[UserBean.getId] 返回 int，同类内唯一稳定）。
 *
 * [followed] 是关注态的**不可变快照**，刻意不直接读 [user] 的 `isIs_followed`：[UserBean] 是可变
 * 的 legacy bean，而刷新前后 / 乐观更新前后的两代条目共享同一个实例——把渲染建立在可变字段上，
 * 「关注态变了」在 DiffUtil 眼里就是不存在的（oldItem 的内容会被一起改掉）。换实例 + 值字段
 * 才让内容比较成立。bean 本身仍会被同步（下游 legacy 路径要读），但那是 transform 之外的事，
 * 见 [LikeUsersFeedFragment.applyFollowed]。
 */
class LikeUserFeedItem(
    val user: UserBean,
    val followed: Boolean = user.isIs_followed,
) : FeedItem {

    override val feedKey: Any get() = user.id

    override fun equals(other: Any?): Boolean =
        other is LikeUserFeedItem && other.user === user && other.followed == followed

    override fun hashCode(): Int = System.identityHashCode(user) * 31 + followed.hashCode()

    fun withFollowed(value: Boolean): LikeUserFeedItem =
        if (followed == value) this else LikeUserFeedItem(user, value)
}

/**
 * 点赞用户数据源：包裹 [SimpleUserRepo]（cursor = next_url）。
 * load(null) → initApi(getUsersWhoLikeThisIllust)；load(cursor) → setNextUrl + initNextApi(getNextSimpleUser)。
 * 请求发起切 IO（awaitFirstValue 内部 subscribeOn(io)，此处外层 withContext 只为对齐既有桥接写法）；
 * 映射切 Default。短页 / 无 next_url（空串）即到底返回 null。
 *
 * 零 Fragment 捕获：只吃 illustId(Int)，repo 内部持 illustId + next_url 分页状态。
 */
class LikeUsersFeedSource private constructor(
    private val repo: RemoteRepo<ListSimpleUser>,
) : FeedSource<String> {

    constructor(illustId: Int) : this(SimpleUserRepo(illustId))
    constructor(novelId: Long) : this(NovelBookmarkUserRepo(novelId))

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ListSimpleUser = if (cursor == null) {
            withContext(Dispatchers.IO) { repo.initApi() }.awaitFirstValue()
        } else {
            withContext(Dispatchers.IO) {
                repo.setNextUrl(cursor)
                repo.initNextApi()
            }.awaitFirstValue()
        }
        val items: List<FeedItem> = withContext(Dispatchers.Default) {
            resp.list.orEmpty().map { LikeUserFeedItem(it) }
        }
        return FeedPage(items, resp.nextUrl?.takeIf { it.isNotEmpty() })
    }
}
