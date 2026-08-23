package ceui.pixiv.ui.muted

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.RecySimpleUserBinding
import ceui.loxia.User
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「屏蔽画师」列表页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentMutedUser] +
 * SimpleUserAdapter(muted)）。
 *
 * 宿主是共享的 [ceui.lisa.fragments.FragmentViewPager]（VIEW_PAGER_MUTED 分支）的 tab 1——
 * toolbar / tabs 归 pager，本页用**裸 fragment_feed**（无自带 toolbar）。pager 通过
 * `((Toolbar.OnMenuItemClickListener) currentFragment).onMenuItemClick(item)` 派发菜单点击，
 * 故本类**必须**实现 [Toolbar.OnMenuItemClickListener]；tab 1 的菜单是 delete_muted_history，
 * 其中只有 action_delete（全部删除）归本页，导入 / 导出由 pager 自理。
 *
 * **不复用 [ceui.pixiv.ui.common.UserFeedFragment]**：它渲染的是 recy_user_preview（头像 + 3 张
 * 预览插画 + 关注按钮），条目是 loxia [ceui.loxia.UserPreview]，且会 feedLikeSync 把用户喂进全局
 * 关注态。而屏蔽画师这行要的是 recy_simple_user（头像 + 名字 + 关注按钮，长按解除屏蔽），
 * 卡形与列表条目结构都对不上，故自建 renderer 精确复刻 muted 模式。
 *
 * **陈旧用户快照不喂全局池**：屏蔽记录存的是 mute 那一刻冻结的 User JSON（general/mute 表），
 * 之后再没更新。喂进 ObjectPool / 关注态会拿旧值盖掉本会话更新的收藏 / 关注态（同
 * WatchLaterFeedFragment.poolableBeansOf=emptyList 的成因）。本页自建 renderer 天然没有池写入路径，
 * 构造上即安全——所以下面全程不碰 ObjectPool。关注 / 解除屏蔽都是**用户显式点击**触发的网络 / DB
 * 写入，与「加载时静默喂池」是两回事，照 legacy 复刻不受此约束影响。
 */
class MutedUserFeedFragment : FeedFragment(), Toolbar.OnMenuItemClickListener {

    override val feedViewModel by feedViewModels(autoLoad = true) {
        // 零捕获：source 不吃任何参数，DB 走 application context。
        MutedUserFeedSource()
    }

    /**
     * 头像 Glide 请求管理器，建一次复用（对齐 [ceui.pixiv.ui.common.UserFeedFragment.userGlide]）：
     * bind 加载 / recycle 清理都走它，避免每处 `Glide.with(view)` 递归找承载 fragment。
     */
    private val userGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(userGlide)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(mutedUserRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐 legacy ListFragment 的 LinearItemDecoration(12dp)。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    private fun mutedUserRenderer() = feedRenderer<MutedUserFeedItem, RecySimpleUserBinding>(
        inflate = RecySimpleUserBinding::inflate,
        create = { cell ->
            // 点 item → 进画师页；长按 item → 解除屏蔽（muted 模式独有，见 SimpleUserAdapter）。
            cell.binding.root.setOnClick { openProfile(cell.item.user) }
            cell.binding.root.setOnLongClickListener {
                unmute(cell.item.user)
                true
            }
            // 点按钮 → 关注 / 取关切换；长按按钮 → 私密关注（沿用 legacy）。
            cell.binding.postLikeUser.setOnClick { toggleFollow(cell) }
            cell.binding.postLikeUser.setOnLongClickListener {
                privateFollow(cell)
                true
            }
        },
        recycle = { cell -> userGlide.clear(cell.binding.userHead) },
    ) { cell -> bindRow(cell) }

    private fun bindRow(cell: FeedCell<MutedUserFeedItem, RecySimpleUserBinding>) {
        val b = cell.binding
        val user = cell.item.user
        b.userName.text = user.name
        userGlide.load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(b.userHead)
        renderFollow(b, user.is_followed == true)
    }

    /** 关注按钮文案：已关注 / 关注。 */
    private fun renderFollow(b: RecySimpleUserBinding, followed: Boolean) {
        b.postLikeUser.text =
            getString(if (followed) R.string.post_unfollow else R.string.post_follow)
    }

    private fun openProfile(user: User) {
        Common.showUser(requireContext(), user)
    }

    /** 关注 / 取关切换：就地翻 bean 状态 + 按钮文案（对齐 legacy 的命令式重绘，不 notify）。 */
    private fun toggleFollow(cell: FeedCell<MutedUserFeedItem, RecySimpleUserBinding>) {
        val user = cell.item.user
        if (user.is_followed == true) {
            PixivOperate.postUnFollowUser(user.id.toInt())
            user.is_followed = false
        } else {
            PixivOperate.postFollowUser(user.id.toInt(), PixivActions.defaultFollowRestrict())
            user.is_followed = true
        }
        renderFollow(cell.binding, user.is_followed == true)
    }

    /** 长按按钮 = 私密关注（沿用 legacy 的长按语义）。 */
    private fun privateFollow(cell: FeedCell<MutedUserFeedItem, RecySimpleUserBinding>) {
        val user = cell.item.user
        PixivOperate.postFollowUser(user.id.toInt(), Params.TYPE_PRIVATE)
        user.is_followed = true
        renderFollow(cell.binding, true)
    }

    /**
     * 长按 item = 解除屏蔽：DB 写入切 IO（[PixivOperate.unMuteUser] 内含 unMuteTag + toast），
     * 落盘后把该条从列表摘掉，并把分页游标回退一格。
     *
     * 用 Fragment 级 [lifecycleScope]（不是 viewLifecycleOwner）：解除屏蔽是本地 DB 写，视图若在
     * 途中销毁也应照常删完；[feedViewModel] 比视图活得久，摘条 / 回退游标都安全。
     */
    private fun unmute(user: User) {
        val id = user.id
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { PixivOperate.unMuteUser(user) }
            feedViewModel.removeItems { it is MutedUserFeedItem && it.user.id == id }
            rewindCursorAfterRemoval()
        }
    }

    /**
     * 删一条后把 offset 游标回退到「当前列表条数」——[MutedUserFeedSource] 的游标是绝对 DB offset，
     * 删除只改内存列表不动游标，下一页会从旧 offset 起读、跳过一条。对齐 legacy 每页用
     * `allItems.size()` 当 offset 的自愈语义（删除后 DB 整体前移一格，取「已展示条数」恰好续上）。
     */
    private fun rewindCursorAfterRemoval() {
        feedViewModel.adoptCursor(feedViewModel.uiState.value.items.size)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        // 宿主可能在 config-change / 进程恢复后把菜单委派给一个从未 attach 的孤儿实例（pager 重建
        // mFragments 却复用旧 fragment）；未 attach 时读 [feedViewModel] 会在取 ViewModelStore 时抛
        // IllegalStateException。对齐 MutedTagsFeedFragment 的 activity 守卫：未 attach 直接吃掉事件。
        val activity = activity ?: return true
        if (item.itemId == R.id.action_delete) {
            if (feedViewModel.uiState.value.items.isEmpty()) {
                Common.showToast(getString(R.string.string_388))
            } else {
                confirmDeleteAll(activity)
            }
        }
        return true
    }

    private fun confirmDeleteAll(activity: android.app.Activity) {
        WitDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.string_216)
            .setMessage(R.string.string_389)
            .addAction(R.string.string_218) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_219, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                deleteAll()
            }
            .create()
            .show()
    }

    private fun deleteAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAllMutedUsers()
            }
            Common.showToast(getString(R.string.string_220))
            // refresh 重查（已清空）→ 框架自动切空态，等价 legacy 的 clear + setEmptyStateVisible。
            feedViewModel.refresh()
        }
    }
}

/**
 * 屏蔽画师条目持 mute 那一刻冻结的 [User] JSON。
 * feedKey 用画师 id。
 */
class MutedUserFeedItem(val user: User) : FeedItem {
    override val feedKey: Any get() = user.id
}

/**
 * 屏蔽画师数据源：mute 表分页（offset 游标），每页 [PAGE_SIZE] 条。
 *
 * 每行的 tagJson 反序列化成 [User]，坏数据用 runCatching 跳过（不崩一页）。游标推进按**DB 行数**
 * 而非解析成功的条目数——即便中间有坏行，offset 也照 `getMutedUser` 实际取回的行数往后走，不会卡死。
 * 短页（行数 < PAGE_SIZE）即到底返回 null：legacy `hasNext()` 恒 true 会让 loadMore 在空的下一页
 * 空转（框架的空页追载 guard 兜得住，但短页归 null 更干净且正确）。
 *
 * 零 Fragment 捕获：无参构造，DB 走 [Shaft.getContext] 的 application context。
 * 不写任何 ObjectPool / 关注态——陈旧 bean 喂池的害处见 [MutedUserFeedFragment] 类文档。
 */
class MutedUserFeedSource : FeedSource<Int> {

    override suspend fun load(cursor: Int?): FeedPage<Int> {
        val offset = cursor ?: 0
        return withContext(Dispatchers.IO) {
            val rows = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getMutedUser(PAGE_SIZE, offset)
            val items: List<FeedItem> = rows.mapNotNull { entity ->
                val user = runCatching {
                    Shaft.sGson.fromJson(entity.tagJson, User::class.java)
                }.getOrNull()
                user?.let { MutedUserFeedItem(it) }
            }
            val nextCursor = if (rows.size < PAGE_SIZE) null else offset + rows.size
            FeedPage(items, nextCursor)
        }
    }

    companion object {
        /** 每页条数，对齐 legacy ListFragment.PAGE_SIZE。 */
        private const val PAGE_SIZE = 20
    }
}
