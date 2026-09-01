package ceui.pixiv.ui.user

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UserIllustFirstPageListener
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.api.Client
import ceui.pixiv.db.queue.WorkType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.bulk.startAuthorWorksBulkDownload
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

/** 通用空态下方换行追加「作品已被屏蔽设置全部隐藏」；未命中时原样返回。 */
internal fun filteredEmptyStateText(
    base: CharSequence,
    allItemsFiltered: Boolean,
    context: Context,
): CharSequence = if (allItemsFiltered) {
    "$base\n${context.getString(R.string.feed_all_items_filtered)}"
} else {
    base
}

/**
 * 「某人创作的插画」列表页(feeds 框架版,替代 legacy FragmentUserIllust + UserIllustRepo)。
 * 卡片用基类 [IllustFeedFragment] 的标准瀑布流插画卡(点赞 / 长按菜单 / 详情跟滚都自带)。
 *
 * 同时是漫画页 [UserMangaFeedFragment] 的基类:pixiv 把漫画当插画的一个 type,整页只有
 * 接口 type / 标题 / 精华 dataType / 跳转 Kind / 有无「下载全部」几处不同,见下面几个 open seam。
 *
 * 与 legacy 的行为对齐点:
 * - 两种形态:内嵌(UserV3WorkTabFragment,showToolbar=false)/ 独立(TemplateActivity「插画作品」,
 *   showToolbar=true 带 toolbar 菜单);
 * - 首屏把数据回调给宿主([UserIllustFirstPageListener])聚合「标签筛选条」,进主页零额外请求;
 * - 「跳转」:UserIllustJumpHelper 选好 offset/日期后整体 replace 本 fragment,首屏从 offset 拉,
 *   带 targetDate 时首屏定位到该日期的作品并高亮;
 * - toolbar 菜单:收藏到精华(action_bookmark)、跳转(action_jump)、下载全部插画(action_download_all,
 *   拿到总数 >0 才显)。
 */
open class UserIllustFeedFragment : IllustFeedFragment() {

    // ── 子类可换的几处「作品类型」差异（漫画见 UserMangaFeedFragment）──────────────
    // pixiv 把漫画当插画的一个 type：同一个 /v1/user/illusts 接口、同一套卡片和菜单，
    // 只有下面这几项不同，所以是覆写几个 seam 而不是复制一整页。
    // 全部走 get()/lazy 语义：feedViewModel 是 lazy 委托，首次访问时子类已构造完（别改成
    // eager val，那会在基类 init 阶段读到子类还没初始化的字段）。

    /** 接口 type 参数：[Params.TYPE_ILLUST] / [Params.TYPE_MANGA]。 */
    protected open val workType: String get() = Params.TYPE_ILLUST

    /** 独立形态的 toolbar 标题。 */
    protected open val titleRes: Int get() = R.string.string_246

    /** legacy 精华功能的 dataType 路由字面量（按它分支重建页面），不是展示文案，别本地化。 */
    protected open val featureDataType: String get() = TemplateRoute.USER_ILLUSTS.key

    /** 「跳转」对话框按哪种作品数分页。 */
    protected open val jumpKind: UserIllustJumpHelper.Kind get() = UserIllustJumpHelper.Kind.ILLUST

    /** 是否提供「下载全部」菜单（数量口径是 total_illusts，漫画侧对齐 legacy 不提供）。 */
    protected open val supportsDownloadAll: Boolean get() = true

    /** 「跳转」选定 offset/日期后用来 replace 自己的新实例。 */
    protected open fun newInstanceForJump(offset: Int, pickedDate: String?): androidx.fragment.app.Fragment {
        return newInstance(userId, showToolbar, offset, pickedDate)
    }

    protected val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }
    protected val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG)
    }
    private val initialOffset: Int by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.INITIAL_OFFSET, 0)
    }

    /** 一次性:首屏定位到该日期后清空(并从 args 抹掉,旋转不再滚)。 */
    private var targetDate: String? = null

    /** 首屏只交付一次(标签回调 + targetDate 定位);旋转重建后 VM 已有数据会再交付一次(宿主自去重)。 */
    private var firstPageDelivered = false

    /** 作者作品页状态归 VM(数据不塞 Fragment):旋转/视图重建不重查。toolbar 菜单与「全被过滤」空态判断共用。 */
    private val userWorksStateViewModel: UserWorksStateViewModel by viewModels()

    // 内嵌 UserActivityV3 tab(无底栏)时,列表底部补手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    override val applyBottomSafeInset: Boolean = true

    /** 作者作品被屏蔽设置（AI / R18 / 标签…）整页滤空时，在通用空态下方换行追加说明。 */
    override val emptyStateText: CharSequence
        get() = filteredEmptyStateText(
            super.emptyStateText,
            userWorksStateViewModel.allItemsFiltered.value,
            requireContext(),
        )

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定:userId / offset / type 先取成局部值,不把 Fragment 钉进长命 VM
        val uid = userId
        val offset = initialOffset
        val type = workType
        // userWorksStateViewModel 是 ViewModel 实例（非 Fragment），mapper 里借它记录「整页被过滤滤空」。
        val stateVm = userWorksStateViewModel
        pixivFeedSource({
            Client.appApi.getUserCreatedIllusts(uid, type, offset.takeIf { it > 0 })
        }) { resp, _ -> mapUserIllustPage(resp.displayList, stateVm) }
    }

    // showToolbar 是运行时参数,系统重建只走无参构造,不能靠构造器传 contentLayoutId,
    // 改在这里按参数选骨架(两张布局都带同结构的 feed_root)。同时读一次性 targetDate。
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        targetDate = requireArguments().getString(Params.TARGET_DATE)
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (showToolbar) setupToolbar(view)

        // 首屏内容到手 → 交付宿主(标签条聚合)+ 处理 targetDate 定位。UDF:观察 uiState,
        // 取第一次非空 items(缓存/网络首屏都算),不侵入基类的渲染链路。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state ->
                    if (!firstPageDelivered && state.items.isNotEmpty()) {
                        firstPageDelivered = true
                        deliverFirstPage(state.items)
                    }
                }
            }
        }

        // allItemsFiltered 由 mapper 在后台设置，render 可能先于它跑；停在空态时补一次文案。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                userWorksStateViewModel.allItemsFiltered.collect {
                    if (feedViewModel.uiState.value.showEmptyState) {
                        feedBinding.feedStateText.text = emptyStateText
                    }
                }
            }
        }
    }

    /** 首屏交付:标签条回调(父 fragment 优先,退回 activity)+ targetDate 定位。 */
    private fun deliverFirstPage(items: List<FeedItem>) {
        val beans = items.filterIsInstance<IllustFeedItem>().map { it.illust }
        val listener = parentFragment as? UserIllustFirstPageListener
            ?: activity as? UserIllustFirstPageListener
        listener?.onUserIllustFirstPage(beans)
        scrollToTargetDate(beans)
    }

    /** 定位到首个创建日期 ≤ targetDate 的作品并轻微放大高亮;一次性消费。 */
    private fun scrollToTargetDate(beans: List<Illust>) {
        val date = targetDate ?: return
        if (beans.isEmpty()) return
        var hit = beans.indexOfFirst {
            val cd = it.create_date
            // ISO 字符串前 10 位即 yyyy-MM-dd,可按字典序比较
            cd != null && cd.length >= 10 && cd.substring(0, 10) <= date
        }
        if (hit < 0) hit = beans.size - 1
        targetDate = null
        requireArguments().remove(Params.TARGET_DATE) // 一次性,旋转不再滚

        val pos = hit
        val list = feedBinding.feedListView
        list.postDelayed({
            if (view == null) return@postDelayed
            when (val lm = list.layoutManager) {
                // SGLM.scrollToPosition 会错乱 span 分配导致 decoration 边距错位,用带 offset 版
                is StaggeredGridLayoutManager -> {
                    lm.scrollToPositionWithOffset(pos, 0)
                    list.post { lm.invalidateSpanAssignments() }
                }
                else -> list.scrollToPosition(pos)
            }
            highlightItemAt(pos, 5)
        }, 200L)
    }

    private fun highlightItemAt(adapterPos: Int, triesLeft: Int) {
        val list = _feedListOrNull() ?: return
        val vh = list.findViewHolderForAdapterPosition(adapterPos)
        if (vh == null) {
            if (triesLeft > 0) {
                list.postDelayed({ highlightItemAt(adapterPos, triesLeft - 1) }, 100L)
            }
            return
        }
        val v = vh.itemView
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220L)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
            }.start()
    }

    private fun _feedListOrNull(): RecyclerView? = if (view == null) null else feedBinding.feedListView

    // ── toolbar(独立形态)────────────────────────────────────────────
    private fun setupToolbar(view: View) {
        val binding = FragmentToolbarFeedBinding.bind(view)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(titleRes)
        binding.toolbar.inflateMenu(R.menu.local_save)
        if (supportsDownloadAll) {
            // 「下载全部」单独挂 user_illust_actions,不并进 local_save(免得共用 local_save 的别的页多出这项)
            binding.toolbar.inflateMenu(R.menu.user_illust_actions)

            val downloadAllItem: MenuItem? = binding.toolbar.menu.findItem(R.id.action_download_all)
            // 数量没拿到前先藏,免得点了报「加载中」;拉到 >0 再显
            downloadAllItem?.isVisible = false
            userWorksStateViewModel.ensureLoaded(userId)
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    userWorksStateViewModel.total.collect { total ->
                        if (total > 0) downloadAllItem?.isVisible = true
                    }
                }
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bookmark -> { saveToFeature(); true }
                R.id.action_jump -> { showJumpDialog(); true }
                R.id.action_download_all -> { confirmDownloadAll(); true }
                else -> false
            }
        }
    }

    /** 收藏到精华(对齐 legacy:uuid 固定 = 同页重复收藏只留一份;dataType 是路由字面量,别本地化)。 */
    private fun saveToFeature() {
        // 快照在主线程取(列表状态归主线程),整表 gson 序列化 + 同步 Room 写挪 IO——
        // 跳页后列表可达数百条,本仓有主线程写 Room 出 ANR 的前科(addTask 那次)。
        val beans = currentIllustItems().map { it.illust }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val entity = FeatureEntity().also {
                    it.uuid = "$userId$featureDataType"
                    it.isShowToolbar = showToolbar
                    it.dataType = featureDataType
                    it.illustJson = Common.cutToJson(beans)
                    it.userID = userId
                    it.dateTime = System.currentTimeMillis()
                }
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertFeature(entity)
            }
            Common.showToast("已收藏到精华")
        }
    }

    private fun showJumpDialog() {
        UserIllustJumpHelper.showJumpDialog(
            requireActivity(), userId, jumpKind,
        ) { offset, pickedDate ->
            if (isAdded && !isStateSaved) {
                parentFragmentManager.beginTransaction()
                    .replace(id, newInstanceForJump(offset, pickedDate))
                    .commit()
            }
        }
    }

    private fun confirmDownloadAll() {
        val total = userWorksStateViewModel.total.value
        if (total <= 0) return // 按钮该藏着,兜底
        val authorName = currentIllustItems().firstOrNull()?.illust?.user?.name ?: "user"
        showDownloadAllConfirm(authorName, total)
    }

    private fun showDownloadAllConfirm(authorName: String, total: Int) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.bulk_user_menu_download_all_illust)
            .setMessage(getString(R.string.bulk_user_download_all_illust_confirm, authorName, total))
            .addAction(0, getString(R.string.cancel), WitDialogAction.ACTION_PROP_NEUTRAL) { d, _ -> d.dismiss() }
            .addAction(0, getString(android.R.string.ok)) { d, _ ->
                d.dismiss()
                startAuthorWorksBulkDownload(
                    requireActivity(), userId, WorkType.ILLUST, authorName,
                )
            }
            .show()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userID: Long,
            showToolbar: Boolean,
            initialOffset: Int = 0,
            targetDate: String? = null,
        ): UserIllustFeedFragment {
            return UserIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userID)
                    putBoolean(Params.FLAG, showToolbar)
                    putInt(Params.INITIAL_OFFSET, initialOffset)
                    if (targetDate != null) putString(Params.TARGET_DATE, targetDate)
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有,放伴生对象保证零捕获。 */
        private fun mapUserIllustPage(
            illusts: List<Illust>,
            stateVm: UserWorksStateViewModel
        ): List<FeedItem> {
            val items = illusts.mapNotNull { illust ->
                // 画师本人作品页：让步「屏蔽画师」过滤（否则屏蔽了本画师后，整页全被滤空 → 空页追载
                // 狂翻 offset）。R18/标签/AI/作品ID 过滤照常。
                IllustFeedItem.of(illust, skipMuteUserFilter = true)
            }
            // 空态要能说清「作者有作品，只是被你的屏蔽设置全滤掉了」，和「作者确实没作品」区分开。
            stateVm.reportPageFiltered(rawCount = illusts.size, shownCount = items.size)
            return items
        }
    }
}

/**
 * 作者作品页的小状态 VM：作品总数（下载全部） + 作品是否被屏蔽设置整页滤空（空态文案）。
 * 单独一个小 VM 而不是塞进 FeedViewModel:它跟列表翻页状态没关系(数据归 ViewModel + 按需建小 VM)。
 */
class UserWorksStateViewModel : ViewModel() {

    private val _total = MutableStateFlow(-1)

    /** -1 = 还没拉到;仅 >0 时「下载全部」可见。 */
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _allItemsFiltered = MutableStateFlow(false)

    /** 服务端有作品、但被本地过滤链（AI / R18 / 标签 / 作品 ID）整页滤空（供空态文案判断）。 */
    val allItemsFiltered: StateFlow<Boolean> = _allItemsFiltered.asStateFlow()

    private var started = false

    fun ensureLoaded(uid: Long) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                _total.value = Client.appApi.getUserProfile(uid).profile?.total_illusts ?: 0
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                // 菜单项保持隐藏即可,失败允许下次进页重试
                Timber.w(ex, "拉取作者作品总数失败")
                started = false
            }
        }
    }

    /**
     * mapper 在后台线程上报一页的过滤结果。
     * 原始非空、展示为空 → 置 true；展示非空 → 置 false；原始就是空页（空页追载翻到底）不动——
     * 否则「第一页 30 条全被滤掉、追载第二页服务端返回空」会把刚记下的 true 抹掉。
     */
    fun reportPageFiltered(rawCount: Int, shownCount: Int) {
        if (shownCount > 0) {
            _allItemsFiltered.value = false
        } else if (rawCount > 0) {
            _allItemsFiltered.value = true
        }
    }
}
