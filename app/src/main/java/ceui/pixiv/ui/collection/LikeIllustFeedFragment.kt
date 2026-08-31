package ceui.pixiv.ui.collection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「某人收藏的插画/漫画」列表页（feeds 框架版，替代 legacy FragmentLikeIllust +
 * LikeIllustRepo + IAdapterWithStar）。卡片用基类的标准瀑布流插画卡。
 *
 * 与 legacy 的行为对齐点：
 * - starType 公开/私密两 tab（FragmentCollection），私密 tab 懒加载（autoLoad=false，
 *   宿主 pager 需 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT），不替用户偷偷请求私密收藏；
 * - 「按标签筛选」页选完标签广播 FILTER_ILLUST 回流，匹配 starType 才认领并刷新；
 *   初始标签可由入参带入（同义词词典管理页跳转，issue #904）；
 * - 「过滤无效收藏」设置：失效作品（!isVisible）/ user 缺失 / user.id==0 都在设置开启时
 *   才过滤；关闭时插画收藏也放行 visible=false 的失效条目（对齐小说侧与设置文案，区别于
 *   其它 feed 的通用过滤链恒过滤 !isVisible）；
 * - 自己的收藏页 + 「收藏页隐藏收藏按钮」设置 → 卡片不显示爱心（对齐 IAdapterWithStar）；
 * - 带 toolbar 形态（TemplateActivity「插画/漫画收藏」）沿用 local_save 菜单，
 *   只响应「收藏到精华」（legacy 的 jump/add 两项在本页本来就是死的）。
 */
class LikeIllustFeedFragment : IllustFeedFragment() {

    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }
    private val starType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.STAR_TYPE) ?: Params.TYPE_PUBLIC
    }
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG)
    }

    /** 标签过滤条件归 VM：旋转/视图重建后过滤不丢；source 从这里读，不用捕获 Fragment。 */
    private val filterViewModel: LikeIllustFilterViewModel by viewModels()

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定（见 feedViewModels 文档）：lambda 只捕获局部值和兄弟 VM
        //（filterViewModel 与列表 VM 同一 ViewModelStore、同生命周期），不捕获 Fragment
        val userId = userId
        val starType = starType
        val filter = filterViewModel.also {
            // 列表 VM 首次创建时才走到这里：用入参初始化标签过滤（issue #904 跳转带初始标签）；
            // 旋转复用旧 VM 不重跑，保留用户当下改过的过滤条件
            it.tag = requireArguments().getString(Params.KEY_WORD).orEmpty()
        }
        pixivFeedSource({
            Client.appApi.getUserBookmarkedIllusts(
                userId, starType, filter.tag.takeIf { tag -> tag.isNotEmpty() }
            )
        }) { resp, _ -> mapLikePage(resp.displayList) }
    }

    // 收藏 Tab 内嵌 UserActivityV3(无底栏)时补底部手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    // (裸 feed 形态才生效,不会重复套)。
    override val applyBottomSafeInset: Boolean = true

    /** 自己的收藏页 + 「隐藏收藏按钮」设置 → 卡片不显示爱心；动态读，设置改完回来即时生效。 */
    override val hideLikeButton: Boolean
        get() = SessionManager.loggedInUid == userId &&
                Shaft.sSettings.isHideStarButtonAtMyCollection()

    /** 「按标签筛选」页的选择回流：公开/私密两 tab 并存，匹配本页 starType 才认领。 */
    private val filterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            if (extras.getString(Params.STAR_TYPE) != starType) return
            filterViewModel.tag = extras.getString(Params.CONTENT).orEmpty()
            forceRefresh()
        }
    }

    // showToolbar 是运行时参数：系统重建 Fragment 只走无参构造，不能靠构造器传
    // contentLayoutId，改在这里按参数选骨架（两张布局都带同结构的 feed_root）
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (showToolbar) {
            val binding = FragmentToolbarFeedBinding.bind(view)
            setUpToolbar(binding, feedBinding.feedListView)
            binding.toolbarTitle.setText(R.string.string_164)
            binding.toolbar.inflateMenu(R.menu.local_save)
            binding.toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_bookmark) {
                    saveToFeature()
                    true
                } else {
                    false
                }
            }
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(filterReceiver, IntentFilter(Params.FILTER_ILLUST))
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(filterReceiver)
        super.onDestroyView()
    }

    /** 收藏到精华（对齐 legacy：前 5 条快照入库，uuid 固定 = 同页重复收藏只留一份）。 */
    private fun saveToFeature() {
        // 快照在主线程取（读的是 VM 当前状态），gson 序列化 + Room 写入切 IO：
        // DAO 是阻塞式的，本页其它 DB 操作也都切了 IO。
        val entity = FeatureEntity().also {
            it.uuid = "$userId$DATA_TYPE_FEATURE"
            it.isShowToolbar = showToolbar
            it.dataType = DATA_TYPE_FEATURE
            it.userID = userId
            it.starType = starType
            it.dateTime = System.currentTimeMillis()
        }
        val beans = currentIllustItems().map { item -> item.illust }
        val appContext = Shaft.getContext()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                entity.illustJson = Common.cutToJson(beans)
                AppDatabase.getAppDatabase(appContext).downloadDao().insertFeature(entity)
            }
            Common.showToast("已收藏到精华")
        }
    }

    companion object {
        /** legacy 精华功能的 dataType 路由字面量（按它分支重建页面），不是展示文案，别本地化。 */
        private const val DATA_TYPE_FEATURE = "插画/漫画收藏"

        /** [initialTag] 初始按标签过滤（同义词词典管理页跳转用，issue #904），null/空 = 不过滤。 */
        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userID: Long,
            starType: String,
            showToolbar: Boolean = false,
            initialTag: String? = null,
        ): LikeIllustFeedFragment {
            return LikeIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userID)
                    putString(Params.STAR_TYPE, starType)
                    putBoolean(Params.FLAG, showToolbar)
                    if (!initialTag.isNullOrEmpty()) {
                        putString(Params.KEY_WORD, initialTag)
                    }
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapLikePage(illusts: List<Illust>): List<FeedItem> {
            // 「过滤无效收藏」设置开启时挡失效作品（!isVisible）+ user 缺失/为 0 的残缺条目；
            // 关闭时不过滤 visible，让已删除/不可见的插画收藏也能显示（与小说侧一致）。
            val filterInvalid = Shaft.sSettings.isFilterInvalidBookmarks
            return illusts.mapNotNull { illust ->
                if (filterInvalid && (illust.user == null || illust.user.id == 0L)) {
                    return@mapNotNull null
                }
                IllustFeedItem.of(illust, skipVisibleFilter = !filterInvalid)
            }
        }
    }
}

/** 标签过滤条件（数据归 VM 约定：旋转存活，feed source 直接读它）。空串 = 不过滤。 */
class LikeIllustFilterViewModel : ViewModel() {
    var tag: String = ""
}
