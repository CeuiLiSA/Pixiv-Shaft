package ceui.pixiv.ui.bookmark

import android.os.Bundle
import android.text.InputType
import android.content.res.ColorStateList
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewbinding.ViewBinding
import ceui.lisa.activities.Shaft
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.databinding.RecySelectTagBinding
import ceui.lisa.model.ListBookmarkTag
import ceui.lisa.models.TagsBean
import ceui.lisa.repo.SelectTagRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.utils.ppppx
import ceui.pixiv.witstudio.dialog.WitSkinManager
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「按标签收藏」的标签列表（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentSB] + SAdapter +
 * fragment_select_tag）。核心收藏入口，从插画/小说详情、瀑布流卡长按等 6+ 处进入。
 *
 * 这不是浏览列表，是一张**表单**的主体：可勾选的收藏夹标签列表。选中态挂在可变的 [TagsBean]
 * （`isSelected`/`is_registered`）上，逐格命令式翻（不走整表 diff，对齐
 * [ceui.pixiv.ui.muted.MutedUserFeedFragment] 的关注按钮）。
 *
 * # 宿主
 *
 * 本 fragment **只渲染列表**（用 [FeedFragment] 默认的 `fragment_feed` 布局），壳全部由
 * [SelectTagBottomSheet] 提供：拖拽把手 / 标题 / 添加标签 / 私密开关 / 提交按钮。原先那套
 * Toolbar + 底部条（fragment_select_tag_feed.xml）随「整页 activity 从右侧 push 进来」一起
 * 撤掉了——收藏是就地完成的轻动作，不该离开当前页。
 *
 * 对宿主暴露三件事：[showAddTagDialog]、[submitStar]、以及内部的选中态收集；其余业务行为
 * 一比一保留 legacy 语义：同义词自动勾选（issue #904）、全选设置。提交这一步已改走
 * [PixivActions] 的限流队列（原因见 [submitStar]），不再自己打接口。
 */
class SelectTagFeedFragment : FeedFragment() {

    private val illustID: Int by lazy { arguments?.getInt(Params.ILLUST_ID) ?: 0 }
    private val type: String by lazy { arguments?.getString(Params.DATA_TYPE) ?: Params.TYPE_ILLUST }
    private val tagNamesArg: List<String> by lazy {
        arguments?.getStringArray(Params.TAG_NAMES)?.toList() ?: emptyList()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：Fragment 参数先读进局部 val，数据源只吃基本类型 + 不可变 list（约定见 feedViewModels 文档）。
        val id = illustID
        val t = type
        val names = tagNamesArg
        SelectTagFeedSource(id, t, names)
    }

    /**
     * 关掉下拉刷新：这是一张收藏表单，不是浏览列表。下拉会重拉整份标签列表、把用户已勾选的标签
     * （以及 addTag 新加的）全部冲掉，语义上有害无益；标签列表进页一次性拉全即可。
     */
    override val refreshEnabled: Boolean = false

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(selectTagRenderer())

    /** 卡间距对齐 legacy ListFragment 默认的 LinearItemDecoration(12dp)（FragmentSB 未覆写列表形态）。 */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
        letSheetTakeOverOverscroll(listView)
    }

    /**
     * 让「列表滚到顶后继续下拉」能交接给宿主 sheet 拖拽关闭。
     *
     * [com.google.android.material.bottomsheet.BottomSheetBehavior.onStartNestedScroll] 里有一句
     * `if (target != nestedScrollingChildRef.get()) return false`，而它记的那个 ref 来自
     * `findScrollingChild()` —— 该方法返回**第一个** `isNestedScrollingEnabled` 的 view。feed 的
     * 层级是 `FrameLayout > SwipeRefreshLayout > RecyclerView`，于是 ref 停在 SwipeRefreshLayout 上
     * （[refreshEnabled] = false 只把它 `isEnabled` 关掉，不影响 nestedScrollingEnabled），而真正
     * 发起嵌套滚动的 target 是 RecyclerView，两者对不上 → 交接被 return false 掉，表现为「列表到顶
     * 后怎么拉 sheet 都不动」，只有从把手/标题这些非滚动区才拖得动。
     *
     * 关掉刷新层的 nested scrolling，`findScrollingChild` 就会继续下探到 RecyclerView，target 与 ref
     * 一致，交接恢复。本页本来就不支持下拉刷新（[refreshEnabled] = false），无副作用；也不去动
     * [ceui.pixiv.feeds.FeedFragment] 的公共实现，免得波及其它关刷新的页面。
     */
    private fun letSheetTakeOverOverscroll(listView: RecyclerView) {
        (listView.parent as? SwipeRefreshLayout)?.isNestedScrollingEnabled = false
    }

    /**
     * 只给**行布局**套 Material3 主题的 inflater。
     *
     * recy_select_tag.xml 用的是 MD3 组件与属性（materialCardViewFilledStyle / MaterialCheckBox /
     * textAppearanceBodyLarge），而本 App 主题继承 QMUI、不含这些属性，不包这层行 inflate 直接崩。
     * 宿主 sheet 的 chrome 靠布局根上的 `android:theme` 拿 MD3，但那只覆盖 sheet 自己的视图树；
     * child fragment 的视图由本 fragment 的 inflater 创建，够不着。
     *
     * **刻意不覆写 `onGetLayoutInflater`**：那样会把 MD3 合并进整棵 feed 视图树，而
     * fragment_feed.xml 的首屏转圈是裸 ProgressBar、无显式 tint，会取到 inflation context 的
     * colorAccent → 被 MD3 顶成基线紫，每次开 sheet 都看得见。收窄到行这一处就没这问题。
     *
     * cloneInContext 而非 `LayoutInflater.from`：保留 AppCompat 装的 Factory2。
     */
    private fun LayoutInflater.withMd3(): LayoutInflater = cloneInContext(
        ContextThemeWrapper(
            context,
            com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar,
        )
    )

    /** 强调色（勾选态）。App 主题色用户可换，故运行时取，不写死在 XML。 */
    private val palette by lazy { V3Palette.from(requireContext()) }

    // ── 每行渲染 + 选中态 ────────────────────────────────────────────────
    /**
     * 复刻 SAdapter：star_size 显示「标签名/译名」，illust_count 复选框即选中态的全部视觉。
     * 复选框自身不独立响应点击（回收复用会脏勾），整行点击统一走 [toggleSelection]。
     */
    private fun selectTagRenderer() = feedRenderer<SelectTagFeedItem, RecySelectTagBinding>(
        inflate = { inflater, parent, attach ->
            RecySelectTagBinding.inflate(inflater.withMd3(), parent, attach)
        },
        create = { cell ->
            cell.binding.illustCount.isClickable = false
            cell.binding.illustCount.isFocusable = false
            // MD3 基线紫 → 用户强调色。只需在 create 时染一次，回收复用不用重染。
            cell.binding.illustCount.buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked),
                ),
                intArrayOf(palette.primary, palette.textSecondary),
            )
            cell.binding.root.setOnClickListener { toggleSelection(cell) }
        },
    ) { cell -> bindTagRow(cell) }

    private fun bindTagRow(cell: FeedCell<SelectTagFeedItem, RecySelectTagBinding>) {
        val tag = cell.item.tag
        val name = tag.name.orEmpty()
        val translated = tag.translated_name
        cell.binding.starSize.text =
            if (!translated.isNullOrEmpty()) "$name/$translated" else name
        cell.binding.illustCount.isChecked = tag.isSelectedLocalOrRemote
    }

    /**
     * 整行点击翻选中态（对齐 SAdapter 的 itemView → checkbox.performClick）：读当前
     * isSelectedLocalOrRemote 取反，setSelectedLocalAndRemote 同时写 isSelected/is_registered，
     * 命令式改复选框视觉，不动列表（不触发整表 diff）。bean 被 [SelectTagFeedItem] 持引用，
     * 提交时按 bean 状态收集。
     */
    private fun toggleSelection(cell: FeedCell<SelectTagFeedItem, RecySelectTagBinding>) {
        val tag = cell.item.tag
        val newValue = !tag.isSelectedLocalOrRemote
        tag.setSelectedLocalAndRemote(newValue)
        cell.binding.illustCount.isChecked = newValue
    }

    // ── 添加标签（宿主 sheet 的「添加标签」按钮调进来）──────────────────────
    fun showAddTagDialog() {
        val activity = activity ?: return
        val builder = WitDialog.EditTextDialogBuilder(activity)
        builder.setTitle("添加标签")
            .setSkinManager(WitSkinManager.defaultInstance(activity))
            .setPlaceholder("请输入标签(收藏夹)名")
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction("取消") { dialog, _ -> dialog.dismiss() }
            .addAction("添加") { dialog, _ ->
                val text = builder.editText.text
                if (text != null && text.isNotEmpty()) {
                    addTag(text.toString())
                    dialog.dismiss()
                } else {
                    Common.showToast("请填入标签")
                }
            }
            .show()
    }

    /**
     * 复刻 FragmentSB.addTag：已存在同名标签 → 勾选并重绑该行；否则 new TagsBean(count 0, selected)
     * 前插到列表顶部并回顶。
     *
     * 「已存在」用 [ceui.pixiv.feeds.FeedViewModel.updateItems] 换一个包同一 bean 的新
     * [SelectTagFeedItem] 实例：feedKey（标签名）不变 → DiffUtil 判定为同一条内容变化 → 复用
     * ViewHolder 原地重绑（[SelectTagFeedItem] 非 data class，靠实例身份触发重绑），checkbox 刷成勾选。
     */
    private fun addTag(tagName: String) {
        val existing = feedViewModel.uiState.value.items
            .filterIsInstance<SelectTagFeedItem>()
            .firstOrNull { it.tag.name == tagName }
        if (existing != null) {
            existing.tag.isSelected = true
            feedViewModel.updateItems<SelectTagFeedItem> {
                if (it.tag.name == tagName) SelectTagFeedItem(it.tag) else it
            }
            return
        }
        val bean = TagsBean().apply {
            count = 0
            isSelected = true
            name = tagName
        }
        feedViewModel.mutateItems { listOf(SelectTagFeedItem(bean)) + it }
        scrollToTop()
    }

    // ── 提交 ────────────────────────────────────────────────────────────
    /**
     * 提交：收集选中标签名 → 走 [PixivActions] 的带标签收藏入口 → 立即回调 [onSuccess]
     * 让宿主收场（sheet 关掉自己）。restrict = 私密开关 ? private : public。
     *
     * 私密开关的真值由宿主持有（开关长在 sheet 的底部条上），所以从参数进来，不再自己读 view。
     *
     * ## 为什么从 legacy 的 RxJava + ErrorCtrl 链换成入队
     *
     * 原先这里直接打 `postLike*WithTags`，与仓库里其他收藏入口并行。两条写路径都拿
     * [ceui.loxia.ObjectPool] 当真源，队列正在冷却时它们会以相反的顺序落到服务端 ——
     * 「卡片上点心（进队列）→ 开 sheet 选标签提交（直发）」这种再普通不过的操作，最终状态
     * 由谁先到决定，而不是由用户最后做的那件事决定。而且带标签和不带标签打的是**同一个**
     * `bookmark/add` 端点，是互相覆盖的关系，共用 dedupeKey 之后连点只发最后一次。
     *
     * 顺带修掉的老问题：原先只发一条 `LIKED_*` 广播，不写 ObjectPool —— 于是按标签收藏之后，
     * 读池渲染的 V3 详情页那颗心还是灰的。现在本地状态由门面统一写（池 + 广播两路都覆盖）。
     *
     * 成功 toast 去掉了，对齐 §4.7 的口径：这一刻请求还没发出去（队列可能正在冷却或被闸门
     * 挡着），报成功是骗用户，而几分钟后终态失败还会补一个「收藏失败」自相矛盾。反馈由那颗
     * 立刻变红的心承担，失败时队列会把它拨回去并广播。sheet 关闭本身也是一次确认。
     */
    fun submitStar(isPrivate: Boolean, onSuccess: () -> Unit) {
        val selectedNames = feedViewModel.uiState.value.items
            .filterIsInstance<SelectTagFeedItem>()
            .filter { it.tag.isSelectedLocalOrRemote }
            .mapNotNull { it.tag.name }

        val restrict = if (isPrivate) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC
        val targetId = illustID.toLong()
        when (type) {
            Params.TYPE_ILLUST ->
                PixivActions.bookmarkIllustWithTags(targetId, restrict, selectedNames)
            Params.TYPE_NOVEL ->
                PixivActions.bookmarkNovelWithTags(targetId, restrict, selectedNames)
            // 未知 type：不猜端点，也不假装成功——直接不收场，sheet 留在原地。
            else -> return
        }
        onSuccess()
    }

    companion object {
        @JvmStatic
        fun newInstance(illustID: Int, type: String?, tagNames: Array<String>?): SelectTagFeedFragment =
            SelectTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.ILLUST_ID, illustID)
                    putString(Params.DATA_TYPE, type)
                    putStringArray(Params.TAG_NAMES, tagNames)
                }
            }
    }
}

/**
 * 一行收藏夹标签。选中态挂在可变的 [tag]（`isSelected`/`is_registered`）上，逐格命令式翻，
 * 故本类**刻意非 data class**：内容相等退化为实例身份，让 [SelectTagFeedFragment.addTag] 能靠
 * 换新实例（feedKey 不变）触发 DiffUtil 重绑该行。feedKey 用标签名（同类内唯一稳定）。
 */
class SelectTagFeedItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any get() = tag.name ?: tag
}

/**
 * 「按标签收藏」数据源：包裹 legacy [SelectTagRepo]，单页（nextCursor 恒 null，对齐 initNextApi=null）。
 *
 * load(null)：IO 上建 + 订阅 [SelectTagRepo.initApi]（api2 拉全量标签 → flatMap 到 api1 拉本作品标签，
 * 顺带把 listTag 存进 repo），await 首值；再在 IO 上跑 [SelectTagRepo.mapper]（同义词词典自动勾选
 * issue #904，含全量 DB 读，绝不能上主线程）+ beforeFirstLoad 全选，最后映射成条目。
 *
 * 零 Fragment 捕获：只吃 illustID/type/tagNames（基本类型 + 不可变 list）。
 */
class SelectTagFeedSource(
    private val illustID: Int,
    private val type: String,
    private val tagNames: List<String>,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 单页：框架只会用 cursor == null 调本源（nextCursor 恒 null，无翻页）。
        val repo = SelectTagRepo(illustID, type, tagNames)
        val resp: ListBookmarkTag = withContext(Dispatchers.IO) { repo.initApi() }.awaitFirstValue()
        val tags: List<TagsBean> = withContext(Dispatchers.IO) {
            // 同义词词典自动勾选（issue #904）在 mapper 里做，跑后台线程（读全量词典 DB），不阻塞 UI。
            repo.mapper().apply(resp)
            // beforeFirstLoad 全选：设置开 + (tagNames 空 或 该标签命中作品标签) → 勾选。
            if (Shaft.sSettings.isStarWithTagSelectAll) {
                resp.list?.forEach { tag ->
                    if (tagNames.isEmpty() || tagNames.contains(tag.name)) {
                        tag.isSelected = true
                    }
                }
            }
            resp.list.orEmpty()
        }
        val items: List<FeedItem> = tags.map { SelectTagFeedItem(it) }
        return FeedPage(items, null)
    }
}
