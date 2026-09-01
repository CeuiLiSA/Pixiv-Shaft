package ceui.pixiv.ui.pinned

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.CellItemPinnedUserBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.User
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我置顶的内容」的「作者」tab（[PinnedTabsFragment] 的右页）。
 *
 * 数据是 general_table 里 recordType=PINNED_USER 的行，写入口在作者主页「更多」菜单
 * （[ceui.pixiv.db.EntityWrapper.pinUser]）。纯本地查询，无分页无网络。
 *
 * 和隔壁 [PinnedTagsFragment] 一样：toolbar / 标题 / 清空都归宿主 tab 页，本页只出列表；
 * 每次进入 STARTED 主动重查一次 DB —— 用户刚在作者页置顶完退回来就得看到。
 */
class PinnedUsersFragment : FeedFragment() {

    // 裸 fragment_feed 挂在 tab 宿主下，底部没有导航栏吃 inset，得自己补，
    // 否则最后一张卡片压在手势条底下（同 WatchLaterFeedFragment）。
    override val applyBottomSafeInset: Boolean = true

    // autoLoad = false 的理由同 PinnedTagsFragment：首屏那次也交给下面的 repeatOnLifecycle
    // refresh 负责，否则 VM init 的那次查询必被随后的 refresh 取消，白跑一趟。
    override val feedViewModel by feedViewModels<String>(autoLoad = false) {
        FeedSource { _ ->
            val items = withContext(Dispatchers.IO) {
                loadPinnedUsers().map { PinnedUserItemHolder(it) }
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.refresh()
            }
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐隔壁「标签」tab 与 V3 卡片列表通用的 12dp。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(pinnedUserRenderer())
    }

    /** 宿主的「清空」要先知道这页空不空 —— 空列表不该走二次确认再报一句「已清空」。 */
    fun hasItems(): Boolean = feedViewModel.uiState.value.items.isNotEmpty()

    private fun pinnedUserRenderer() = feedRenderer<PinnedUserItemHolder, CellItemPinnedUserBinding>(
        inflate = CellItemPinnedUserBinding::inflate,
        create = { cell ->
            // 监听只挂一次，点的那一刻再经 cell.itemOrNull 取当下条目（feeds 框架约定）。
            cell.binding.root.setOnClickListener { v ->
                val user = cell.itemOrNull?.user ?: return@setOnClickListener
                // 开 UActivity 而不是直接开 V3：新旧作者页的分发在 UActivity 里，
                // 硬指一个会绕开用户的「使用新版作品页」开关。
                v.context.startActivity(Intent(v.context, UActivity::class.java).apply {
                    putExtra(Params.USER_ID, user.id)
                })
            }
            cell.binding.deletePin.setOnClickListener {
                cell.itemOrNull?.user?.let { onClickUnpin(it) }
            }
        },
        recycle = { it.binding.userAvatar.clearGlideOnRecycle() },
    ) { cell ->
        val user = cell.item.user
        cell.binding.userName.text = user.name
        cell.binding.userAccount.text = "@${user.account.orEmpty()}"
        Glide.with(cell.binding.root.context)
            .load(GlideUtil.getHead(user))
            .into(cell.binding.userAvatar)
    }

    private fun onClickUnpin(user: User) {
        val ctx = context ?: return
        // EntityWrapper 是 app 单例；提前抓好，弹窗动作异步触发时 fragment 可能已 detach。
        val entityWrapper = requireEntityWrapper()
        val appContext = ctx.applicationContext
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(getString(R.string.unpin_user_confirm_message, user.name.orEmpty()))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.unpin_user, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                // 用 fragment 自身的 lifecycleScope：WitDialog 挂在 Activity context 上，
                // 视图已销毁时用户才点确认，碰 viewLifecycleOwner 会直接抛 ISE（同 PinnedTagsFragment）。
                lifecycleScope.launch {
                    entityWrapper.deletePinnedUser(appContext, user.id)
                    feedViewModel.refresh()
                }
                Common.showToast(R.string.unpin_user_success)
                dialog.dismiss()
            }
            .show()
    }

    /** 宿主 tab 页的「清空」转到这里 —— 删库和刷新都在有数据源的这一侧。 */
    fun showClearAllDialog() {
        val ctx = context ?: return
        val entityWrapper = requireEntityWrapper()
        val appContext = ctx.applicationContext
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.clear_pinned_users_msg)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                lifecycleScope.launch {
                    entityWrapper.clearPinnedUsers(appContext)
                    feedViewModel.refresh()
                }
                Common.showToast(R.string.pinned_users_cleared)
                dialog.dismiss()
            }
            .show()
    }
}

/**
 * 顶层函数：[FeedSource] 的 lambda 里不能碰成员方法，否则会隐式捕获 Fragment 实例，
 * 而 source 被 VM 长期持有（零捕获约定见 [ceui.pixiv.feeds.feedViewModels]）。
 */
private fun loadPinnedUsers(): List<User> = PinnedUsers.loadAll(Shaft.getContext())
