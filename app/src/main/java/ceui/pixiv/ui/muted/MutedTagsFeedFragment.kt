package ceui.pixiv.ui.muted

import android.text.InputType
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.RecyMutedTagBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.utils.ppppx
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.witstudio.dialog.WitSkinManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「屏蔽标签」列表页（feeds 框架版，替代 legacy FragmentMutedTags + MutedTagAdapter）。
 *
 * 宿主 [ceui.lisa.fragments.FragmentViewPager] 的屏蔽记录分支独占 toolbar/tab，菜单点击经
 * `((Toolbar.OnMenuItemClickListener) current).onMenuItemClick(item)` 派发给当前页——故本类
 * **必须** implements [Toolbar.OnMenuItemClickListener]，原样复刻 legacy 的
 * action_delete（全部删除）/ action_add（新增，普通 + 正则两档）。导入 / 导出由宿主自理，与本页无关。
 *
 * 用裸 `FeedFragment()`（不自带 toolbar 骨架），默认 LinearLayoutManager 即本页所需。
 */
class MutedTagsFeedFragment : FeedFragment(), Toolbar.OnMenuItemClickListener {

    override val feedViewModel by feedViewModels(autoLoad = true) {
        // 零捕获：source 无参，DB 走 application context（见 MutedTagsFeedSource）。
        MutedTagsFeedSource()
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(mutedTagRenderer())

    /** 12dp 行间距，对齐 legacy ListFragment.verticalRecyclerView 的 LinearItemDecoration。 */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    /**
     * recy_muted_tag 一行：标签名（`#name` / `#name/译名` / 空名走 string_155）、RE 角标（正则模式）、
     * 生效开关、删除（取消屏蔽）按钮——逐条复刻 MutedTagAdapter.bindData。
     */
    private fun mutedTagRenderer(): FeedRenderer<MutedTagFeedItem, RecyMutedTagBinding> =
        feedRenderer<MutedTagFeedItem, RecyMutedTagBinding>(
            inflate = RecyMutedTagBinding::inflate,
            create = { cell ->
                // 删除 = 取消屏蔽：DB 写走 IO，回主线程把这一行摘掉（空了框架自然回落空态）。
                cell.binding.deleteItem.setOnClickListener {
                    val item = cell.itemOrNull ?: return@setOnClickListener
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { PixivOperate.unMuteTag(item.tag) }
                        feedViewModel.removeItems {
                            it is MutedTagFeedItem && it.feedKey == item.feedKey
                        }
                    }
                }
            },
        ) { cell ->
            val tag = cell.item.tag
            val name = tag.name
            cell.binding.starSize.text = when {
                name.isNullOrEmpty() -> getString(R.string.string_155)
                !tag.translated_name.isNullOrEmpty() -> "#${name}/${tag.translated_name}"
                else -> "#${name}"
            }
            cell.binding.sideDecorator.isVisible = tag.filter_mode != 0

            // 生效开关：先摘监听再 setChecked，避免复用/回填时把程序性赋值当成用户操作误写一次（复刻 legacy）。
            cell.binding.isEffective.setOnCheckedChangeListener(null)
            cell.binding.isEffective.isChecked = tag.isEffective
            cell.binding.isEffective.setOnCheckedChangeListener { _, isChecked ->
                tag.isEffective = isChecked
                lifecycleScope.launch(Dispatchers.IO) {
                    PixivOperate.updateTag(tag)
                }
            }
        }

    /**
     * 新增屏蔽标签（原 FragmentMutedTags.addMutedTag）：按 (name, filter_mode) 去重——
     * 命中则 toast「name + 已存在」；否则落库（IO）后头插一条并回顶。
     */
    private fun addMutedTag(tagName: String, filterMode: Int) {
        val exists = feedViewModel.uiState.value.items.any {
            it is MutedTagFeedItem && it.tag.name == tagName && it.tag.filter_mode == filterMode
        }
        if (exists) {
            Common.showToast(tagName + getString(R.string.string_209))
            return
        }
        val bean = TagsBean().apply {
            name = tagName
            filter_mode = filterMode
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { PixivOperate.muteTag(bean) }
            feedViewModel.mutateItems { listOf(MutedTagFeedItem(bean)) + it }
            scrollToTop()
        }
    }

    /**
     * 宿主 toolbar 菜单（R.menu.delete_and_add）委派进来：action_delete 全部删除、action_add 新增。
     * 无论是否命中都返回 true（对齐 legacy），把这两个 id 吃掉。
     */
    override fun onMenuItemClick(item: MenuItem): Boolean {
        val activity = activity ?: return true
        when (item.itemId) {
            R.id.action_delete -> {
                if (feedViewModel.uiState.value.items.isEmpty()) {
                    Common.showToast(getString(R.string.string_215))
                } else {
                    WitDialog.MessageDialogBuilder(activity)
                        .setTitle(getString(R.string.string_216))
                        .setMessage(getString(R.string.string_217))
                        .setSkinManager(WitSkinManager.defaultInstance(activity))
                        .addAction(getString(R.string.string_218)) { dialog, _ -> dialog.dismiss() }
                        .addAction(
                            0,
                            getString(R.string.string_219),
                            WitDialogAction.ACTION_PROP_NEGATIVE,
                        ) { dialog, _ ->
                            dialog.dismiss()
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getAppDatabase(Shaft.getContext())
                                        .searchDao().deleteAllMutedTags()
                                }
                                Common.showToast(getString(R.string.string_220))
                                feedViewModel.refresh()
                            }
                        }
                        .create()
                        .show()
                }
            }

            R.id.action_add -> {
                val builder = WitDialog.EditTextDialogBuilder(activity)
                builder.setTitle(getString(R.string.string_210))
                    .setSkinManager(WitSkinManager.defaultInstance(activity))
                    .setPlaceholder(getString(R.string.string_211))
                    .setInputType(InputType.TYPE_CLASS_TEXT)
                    .setActionContainerOrientation(WitDialogBuilder.VERTICAL)
                    .addAction(getString(R.string.string_212)) { dialog, _ -> dialog.dismiss() }
                    .addAction(getString(R.string.string_437)) { dialog, _ ->
                        val text = builder.editText.text?.toString().orEmpty()
                        if (text.isNotEmpty()) {
                            addMutedTag(text, 1)
                            dialog.dismiss()
                        } else {
                            Common.showToast(getString(R.string.string_214))
                        }
                    }
                    .addAction(getString(R.string.string_213)) { dialog, _ ->
                        val text = builder.editText.text?.toString().orEmpty()
                        if (text.isNotEmpty()) {
                            addMutedTag(text, 0)
                            dialog.dismiss()
                        } else {
                            Common.showToast(getString(R.string.string_214))
                        }
                    }
                    .show()
            }
        }
        return true
    }
}

/**
 * 单条屏蔽标签。屏蔽标签没有 id，身份 = 标签名 + 过滤模式（0 普通 / 1 正则）——
 * 这也正是 addMutedTag 的去重键。生效开关是对 [tag] 的就地翻转，不改 [feedKey]，不触发 diff 重排。
 */
data class MutedTagFeedItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any
        get() = "${tag.name}#${tag.filter_mode}"
}

/**
 * 屏蔽标签数据源：[IllustNovelFilter.getMutedTags] 全量单页，无翻页（nextCursor 恒为 null，
 * 对齐 legacy LocalRepo.next() 返回 null）。游标类型 Int 只是占位、从不使用。
 *
 * 零 Fragment 捕获：无参构造，DB 读走 [Shaft.getContext] 的 application context。
 */
class MutedTagsFeedSource : FeedSource<Int> {

    override suspend fun load(cursor: Int?): FeedPage<Int> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            IllustNovelFilter.getMutedTags().map { MutedTagFeedItem(it) }
        }
        return FeedPage(items, null)
    }
}
