package ceui.pixiv.ui.pinned

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.lisa.databinding.CellItemPinnedTagBinding
import ceui.lisa.databinding.FragmentPinnedTagsBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「侧边栏 → 置顶标签」入口对应的列表页（feeds 框架版）。
 *
 * 入口在 [ceui.lisa.activities.MainActivity.handleDrawerAction] 通过
 * `EXTRA_FRAGMENT = "PinnedTagsList"` 路由到这里。
 *
 * 数据来源是 [ceui.lisa.utils.PixivOperate.insertPinnedSearchHistory] 写入的 search_table，
 * 本地 Room 查询，没有分页也没有网络，因此不接本地优先缓存——数据本身就在本地。
 *
 * 用户从详情页长按置顶 / 取消置顶后回到本页要看到最新结果，所以每次页面进入 STARTED
 * 都主动 [ceui.pixiv.feeds.FeedViewModel.refresh] 重新查一次 DB（对齐旧版 onResume 语义）。
 */
class PinnedTagsFragment : FeedFragment(R.layout.fragment_pinned_tags) {

    private val binding by viewBinding(FragmentPinnedTagsBinding::bind)

    // autoLoad = false：本页刻意每次可见都重查一遍本地库（下面的 repeatOnLifecycle(STARTED) 里
    // refresh），首屏那次也由它负责。留着默认的 autoLoad 只会让 VM 的 init refresh 与它撞在一起，
    // 第一次 DB 查询必被后来的 refresh 取消重来（refresh 永远赢），白跑一趟。
    override val feedViewModel by feedViewModels<String>(autoLoad = false) {
        FeedSource { _ ->
            val items = withContext(Dispatchers.IO) {
                searchDao().getAllPinned()
                    .filterNot { it.keyword.isNullOrBlank() }
                    .map { PinnedTagItemHolder(it) }
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.apply {
            // BaseActivity 走 EdgeToEdge,顶部状态栏 inset 由 runtime padding 处理,不用
            // fitsSystemWindows(那个在 EdgeToEdge host 下会和 bottom inset 一起算导致额外空白)。
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }
        binding.clearAll.setOnClickListener { showClearAllDialog() }

        // STARTED-aware 协程:只在 fragment 可见时才触发 refresh,避免后台 churn。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.refresh()
            }
        }
        // 「清空」按钮只在有内容时露出,基类的 render() 不管页面专属的这块 UI,单独订阅。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state ->
                    binding.clearAll.isVisible = state.items.isNotEmpty()
                }
            }
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 setUpLayoutManager(ListMode.VERTICAL) 的间距：卡片左右/底部 18dp 间隔 +
        // 首项顶部留白,不挂会顶到屏幕边、上下也无空隙。
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(pinnedTagRenderer())
    }

    private fun pinnedTagRenderer() = feedRenderer<PinnedTagItemHolder, CellItemPinnedTagBinding>(
        inflate = CellItemPinnedTagBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener {
                // 标准跳法（见 V3TagFlowView.kt:179-184）：开 SearchActivity，
                // illust tab（index=0），keyword 就是 tag.name。
                // Tag.name 类型是 String?；FeedSource 已经把空 keyword 的 entity 过滤掉，但编译期
                // 不知道这事，加一道防御兜底——免得未来 Tag/SearchEntity 字段改 nullability 时这里
                // 静悄悄传 null keyword 给 SearchActivity。
                val keyword = cell.item.tag.name?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
                startActivity(Intent(requireContext(), SearchActivity::class.java).apply {
                    putExtra(Params.KEY_WORD, keyword)
                    putExtra(Params.INDEX, 0)
                })
            }
            cell.binding.deletePin.setOnClickListener { onClickDeletePinnedTag(cell.item.entity) }
        },
    ) { cell ->
        cell.binding.holder = cell.item
    }

    private fun onClickDeletePinnedTag(entity: SearchEntity) {
        val ctx = context ?: return
        val displayName = entity.keyword.orEmpty()
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(getString(R.string.unpin_tag_confirm_message, displayName))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_443, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                // Fragment 自身的 lifecycleScope,不是 viewLifecycleOwner 的:WitDialog 挂在
                // Activity context 上,不受 Fragment 视图生命周期约束,视图已销毁(切后台被回收/
                // 旋转)时用户才点确认,访问 viewLifecycleOwner 会直接抛 ISE 崩溃。
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { searchDao().deleteSearchEntity(entity) }
                    feedViewModel.refresh()
                }
                // Toast 走 Toaster,窗口挂在栈顶 Activity 上而不是 fragment,已 detach 时也安全；
                // 用 int 资源是为了避免 dialog 回调里 getString() 撞上 fragment 未 attach 抛 ISE。
                Common.showToast(R.string.unpin_tag_success)
                dialog.dismiss()
            }
            .show()
    }

    private fun showClearAllDialog() {
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.clear_pinned_tags_msg)
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { searchDao().deleteAllPinned() }
                    feedViewModel.refresh()
                }
                Common.showToast(R.string.pinned_tags_cleared)
                dialog.dismiss()
            }
            .show()
    }
}

/**
 * 顶层函数（非成员方法）：[PinnedTagsFragment.feedViewModel] 的 [FeedSource] lambda 里要用它，
 * 成员方法会隐式捕获 `this@PinnedTagsFragment`——FeedSource 被 VM 长期持有，会把旋转前的旧
 * Fragment 实例钉在内存里（零捕获约定见 [ceui.pixiv.feeds.feedViewModels] 文档）。
 */
private fun searchDao() = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()
