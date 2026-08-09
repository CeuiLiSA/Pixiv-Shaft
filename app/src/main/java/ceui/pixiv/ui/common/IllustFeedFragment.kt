package ceui.pixiv.ui.common

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.download.IllustDownload
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.actions.PixivActions
import ceui.lisa.view.SpacesItemDecoration
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.utils.pinHostGlide
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import java.util.UUID

/**
 * 插画瀑布流列表页的共享基类（feeds 框架 + legacy 详情/收藏/菜单协同的桥接层）。
 * 子类只需声明数据源（feedViewModels + mapper）和卡片 Renderer。
 *
 * 本类只做**编排**：声明钩子、接线协作件、提供列表状态的读写口。具体的活都在旁边的文件里，
 * 各自可以单独读、单独改：
 *
 * | 关注点 | 去处 |
 * |---|---|
 * | 条目是什么 / 怎么从各上游建出来 / 收藏态怎么变 | [IllustFeedItem]（纯数据，无 Android 依赖，可单测） |
 * | 卡片怎么画 + 收藏动画 | [staggerIllustRenderer] |
 * | 长按菜单 | [showCardMenu] |
 * | 详情回传（续拉页 / 跟滚）+ 收藏广播回流 | [IllustFeedDetailSync] + [FeedLikeSync] |
 * | 列表数据 → ObjectPool / 关注态 | [IllustFeedPoolSync] |
 *
 * 这些协作件都**不依赖继承本类**（[IllustFeedDetailSync] / [IllustFeedPoolSync] / [FeedLikeSync]
 * 是普通类，挂在 viewLifecycleOwner 上），混排页等任何 feeds 页面都能单独挑用。
 *
 * 本类统一提供：
 * - 宿主是 TemplateActivity / 普通 Activity（非 NavHost）：点击开详情走 PageData + Container +
 *   VActivity，不能用 findNavController；
 * - 卡片收藏爱心（私密收藏设置、收藏后自动下载 #880、失败回滚）+ LIKED_ILLUST 广播双向同步；
 * - 详情 pager 续拉的页经 FRAGMENT_ADD_DATA 追加回列表并用 adoptCursor 接管翻页进度；
 * - StaggeredManager 瀑布流（吞 SGLM predictive-layout 在 fling+插页同帧时的 AOSP 内部崩溃）。
 */
abstract class IllustFeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : FeedFragment(contentLayoutId) {

    abstract override val feedViewModel: FeedViewModel<String>

    /** 页面协同状态归 VM：Fragment 重建（旋转/深色切换）后详情回传链路不断。 */
    protected val syncViewModel: IllustFeedSyncViewModel by viewModels()

    /**
     * 卡片图的 Glide 请求管理器。
     *
     * 建一次复用，别在每次 bind / recycle 里 `Glide.with(view)`：那条重载每次都要
     * `findSupportFragment(view, activity)` —— 递归遍历宿主整棵 fragment 树（每个节点一次
     * `getFragments()` 的 ArrayList 分配 + 一次 synchronized）、再从 decor view 找 content root、
     * 再从 ImageView 往上走一遍。一张卡要跑 3 次（主请求 / error 兜底 / recycle 清图），fling 时
     * 约 45 次/秒，全在帧路径上。`Glide.with(Fragment)` 直接命中，无查找；解析结果与
     * `Glide.with(view)` 是同一个 RequestManager（view 本来就在本 Fragment 里），行为等价。
     */
    internal val illustGlide: RequestManager by lazy { Glide.with(this) }

    /**
     * 瀑布流当前列宽（px）。renderer 用它给 Glide 显式 override 请求尺寸。
     * 取 LayoutManager 实时宽度（measure 先于绑定，旋转后已是新方向的值），首帧兜底屏宽。
     */
    internal val illustColumnWidthPx: Int
        get() {
            val listWidth = feedBinding.feedListView.layoutManager?.width?.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            return (listWidth / Shaft.sSettings.lineCount).coerceAtLeast(1)
        }

    /**
     * 详情 pager 回传的 bean 建条目的钩子。R18 专属榜单等「本页语义就是看 R18」的
     * 子类覆盖此方法透传 skipR18Filter，否则续拉的页会被全局 R18 过滤整页清空。
     */
    protected open fun feedItemFromBean(bean: IllustsBean?): IllustFeedItem? {
        return IllustFeedItem.fromBean(bean)
    }

    /**
     * 收藏时是否顺带拉取相关作品（FRAGMENT_ADD_RELATED_DATA 广播回流插入列表）。
     * 只有首页推荐 tab 覆盖为「收藏时显示相关作品」设置，其他列表保持关闭。
     */
    protected open val showRelatedOnStar: Boolean
        get() = false

    /**
     * 条目里需要合入 ObjectPool / 灌关注状态的 bean。默认取插画条目自身的 bean；
     * 携带附属 bean 的条目（排行榜预览头等）由子类覆盖补充。
     *
     * ⚠️ 交出来的 bean 的收藏 / 关注态必须是**当前用户的新鲜值**：本地快照（稍后再看 / 发现池 /
     * 历史）、第三方上报榜单（shaft-api-v2 系）、精简网页 bean（按 Tag 筛选）都不是——那些页面
     * 一律覆写成 `emptyList()`，否则 mergeKeepingExisting 会拿旧值 / 假值盖掉池里更新的状态。
     */
    protected open fun poolableBeansOf(item: FeedItem): List<IllustsBean> {
        return if (item is IllustFeedItem) listOf(item.bean) else emptyList()
    }

    /**
     * 隐藏卡片上的收藏爱心（自己的收藏页 + 「收藏页隐藏收藏按钮」设置，对齐 legacy
     * IAdapterWithStar）。每次 bind 动态读：设置变更后新绑定的卡片即生效（滑动复用 /
     * 下拉刷新），屏幕上已绑定的卡片不会主动重绑——legacy 是建 adapter 时读死，更迟钝。
     */
    internal open val hideLikeButton: Boolean
        get() = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(illustGlide)

        // 广播协同（收藏回流 / 详情 pager 续拉 / 返回跟滚）与 ObjectPool 合池
        // 拆在独立协作件里，生命周期随 viewLifecycleOwner 自理
        IllustFeedDetailSync(
            feedViewModel = feedViewModel,
            listPageUuid = syncViewModel.listPageUuid,
            itemFromBean = ::feedItemFromBean,
            onDetailScrolledTo = ::scrollToDetailPosition,
        ).bind(requireContext(), viewLifecycleOwner)
        IllustFeedPoolSync(syncViewModel, ::poolableBeansOf)
            .bind(viewLifecycleOwner, feedViewModel.uiState)
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return StaggeredManager(
            Shaft.sSettings.lineCount,
            RecyclerView.VERTICAL,
        )
    }

    override fun onListReady(listView: RecyclerView) {
        // recy_illust_stagger 卡片自身无 margin，间距对齐 legacy staggerRecyclerView
        listView.addItemDecoration(SpacesItemDecoration(DensityUtil.dp2px(8.0f)))
    }

    /** 默认就是标准瀑布流插画卡；需要混排其他条目类型的子类自行覆盖再拼上。 */
    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(staggerIllustRenderer())
    }

    // ── 列表状态的读口（renderer / 菜单 / 子类共用）──────────────────────────────

    internal fun currentIllustItems(): List<IllustFeedItem> {
        return feedViewModel.uiState.value.items.filterIsInstance<IllustFeedItem>()
    }

    /** VM 里当前这条作品的最新条目（真源）；已被刷新挤掉则 null。 */
    internal fun currentIllustItem(illustId: Long): IllustFeedItem? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is IllustFeedItem && it.illust.id == illustId } as? IllustFeedItem
    }

    // ── 收藏 ────────────────────────────────────────────────────────────────

    internal fun toggleLike(item: IllustFeedItem) {
        // 单一真源是 item.illust（UI 由它渲染）。bean 可能因上次请求失败与 UI 背离——
        // PixivOperate.postLike 按 bean 当前值决定收藏还是取消，先把 bean 校准回 UI 状态，
        // 保证发出的请求永远与用户看到的操作一致。
        val uiLiked = item.illust.is_bookmarked == true
        item.bean.setIs_bookmarked(uiLiked)
        val willBookmark = !uiLiked
        val illustId = item.illust.id
        // showRelatedOnStar 时 postLike 会顺带拉相关作品并广播 FRAGMENT_ADD_RELATED_DATA；
        // index 是 legacy 位置语义（feeds 接收器改按广播里的作品 id 锚定），照传兼容；
        // 带上本列表 uuid，广播只被发起收藏的列表认领（多个推荐页同时存活时不串扰）。
        //
        // 不再传失败回调：收藏改走 PixivActionQueue 之后，终态失败由队列统一回滚，并带着
        // 相反的值重发一次 LIKED_ILLUST —— 本列表的 FeedLikeSync 收到就把条目拨回去
        //（withBookmarked 幂等）。回调式回滚反而会让队列在冷却几分钟后去碰一个早已销毁的
        // Fragment 持有的 VM。
        PixivOperate.postLike(
            item.bean,
            PixivActions.defaultBookmarkRestrict(),
            showRelatedOnStar,
            feedViewModel.uiState.value.items.indexOfFirst {
                it is IllustFeedItem && it.illust.id == illustId
            },
            syncViewModel.listPageUuid,
        )
        // 乐观状态写进列表条目而不是只写按钮：滑走再滑回不闪色；成功广播回流后是幂等 no-op
        feedViewModel.updateItems(IllustFeedItem::class.java) {
            if (it.illust.id == illustId) it.withBookmarked(willBookmark) else it
        }
        // 收藏后自动下载只在主动收藏（非取消）时触发，避免与「下载时自动收藏」循环联动（#880）
        if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar()) {
            IllustDownload.downloadIllustAllPages(item.bean)
        }
    }

    // ── 详情跳转 ────────────────────────────────────────────────────────────

    /**
     * 交接给详情页 pager 的续读游标。默认就是列表当前游标——pixiv 列表的游标本身就是 nextUrl，
     * 详情页划到底可以照着它继续请求。
     *
     * **本地数据源必须覆写成 null**：详情页 pager 只会把它当 URL 直接请求下一页，喂页号 /
     * offset 进去只会请求失败（对齐 legacy——LocalListFragment 从不 setNextUrl，本地列表
     * 开详情时 PageData.nextUrl 一直是 null）。
     *
     * **结果集被本页自己过滤过的源也必须覆写成 null**：详情 pager 那条回传链复现不了你的过滤
     * （见 [ceui.pixiv.ui.search.SearchIllustFeedFragment] 的覆写）。
     */
    protected open val detailContinuationCursor: String?
        get() = feedViewModel.currentCursor

    internal fun openDetail(item: IllustFeedItem) {
        val illustItems = currentIllustItems()
        val position = illustItems.indexOfFirst { it.illust.id == item.illust.id }
        // uuid 用 VM 里的稳定值：Container 的 map 永不清理，稳定 key 让本列表最多占一个坑
        //（每次打开覆盖上一份快照，对齐 legacy），Fragment 重建后回传广播也仍能认领。
        val pageData = if (position >= 0) {
            // nextUrl 一并交接给 VActivity，详情页 pager 划到底可以继续加载
            PageData(
                syncViewModel.listPageUuid,
                detailContinuationCursor,
                illustItems.map { it.bean },
            )
        } else {
            // 点击项已不在当前列表（刷新竞态等）：单开该作品，绝不错开成第一张。
            // 故意用一次性 uuid：这份单作品 PageData 的 ADD_DATA/SCROLL_TO（index 0）
            // 和主列表无关，不能被上面的接收器认领去把列表滚回顶部。
            PageData(UUID.randomUUID().toString(), null, listOf(item.bean))
        }
        Container.get().addPageToMap(pageData)
        startActivity(Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, position.coerceAtLeast(0))
            putExtra(Params.PAGE_UUID, pageData.getUUID())
        })
    }

    /** 返回时列表跟到详情页正在看的那张（延迟一拍等转场结束）。 */
    private fun scrollToDetailPosition(illustId: Long, pagerIndex: Int) {
        feedBinding.feedListView.postDelayed({
            // feedAdapter 与 _binding 在 onDestroyView 里同一条主线程语句序列先后置 null，
            // 所以这个守卫成立即蕴含 feedBinding 可用
            val adapter = feedAdapter ?: return@postDelayed
            if (view == null) return@postDelayed
            val items = feedViewModel.uiState.value.items
            // pager 的 index 是交接快照里纯插画列表的下标，列表带 header（推荐页排行榜头）
            // 或收藏后相关作品插到中段时不能直接当 adapter 位置用，按作品 id 锚定
            var position = if (illustId > 0) {
                items.indexOfFirst { it is IllustFeedItem && it.illust.id == illustId }
            } else {
                -1
            }
            if (position < 0 && pagerIndex >= 0) {
                // 广播不带 id 时的兜底：第 pagerIndex 个插画条目（跳过混排的非插画条目）
                var remaining = pagerIndex
                position = items.indexOfFirst { it is IllustFeedItem && remaining-- == 0 }
            }
            if (position in 0 until adapter.itemCount) {
                feedBinding.feedListView.smoothScrollToPosition(position)
            }
        }, 200L)
    }
}

/**
 * 插画 feed 页跨 Fragment 重建存活的协同状态（数据归 ViewModel 约定）。
 */
class IllustFeedSyncViewModel : ViewModel() {

    /**
     * 本列表在 Container 里的稳定 PageData key：每次打开详情覆盖同一个坑（Container
     * 永不清理，随机 uuid 会积攒整列表快照）；Fragment 重建后详情回传广播仍能认领。
     */
    val listPageUuid: String = UUID.randomUUID().toString()

    /**
     * 已合入 ObjectPool 的 bean 实例（id → 当前代实例）。刷新会产出同 id 的新实例、
     * 携带更新的服务端数据，实例变了就要重新合入；同一实例重复扫描则跳过。
     *
     * ⚠️ 只挡「同一实例重复合池」。**就地改 bean 字段（如收藏态）这条 map 看不见** ——
     * 那类变更必须由变更点自己同步池，见 [IllustFeedItem.withBookmarked]。
     */
    val pooledBeans = HashMap<Long, IllustsBean>()
}
