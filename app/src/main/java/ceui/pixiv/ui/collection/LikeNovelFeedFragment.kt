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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.setUpToolbar

/**
 * 「某人收藏的小说」列表页（feeds 框架版，替代 legacy FragmentLikeNovel + LikeNovelRepo + NAdapter）。
 * 卡片 / 收藏同步 / 详情跳转 / 骨架图全部复用基类 [NovelFeedFragment] 的主力小说卡（recy_novel）。
 * 插画侧的同族兄弟见 [LikeIllustFeedFragment]（同一套 starType / tag / 过滤无效收藏语义）。
 *
 * 与 legacy 的行为对齐点：
 * - 端点 `/v1/user/bookmarks/novel`，三个入参逐条对齐：userID / starType（公开·私密）/
 *   tag（按标签筛收藏，空 = 不带该 query，对齐 legacy LikeNovelRepo 的 TextUtils.isEmpty 分支）；
 * - starType 公开/私密两 tab（FragmentCollection），私密 tab 懒加载（autoLoad=false，
 *   宿主 pager 需 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT），不替用户偷偷请求私密收藏；
 * - 「按标签筛选」页选完标签广播 FILTER_NOVEL 回流，匹配 starType 才认领并刷新（legacy 是
 *   `repo.setTag(tag)` + `autoRefresh()`，这里是 filterViewModel.tag + forceRefresh()）；
 *   初始标签可由入参带入（同义词词典管理页跳转，issue #904）；
 * - 「过滤无效收藏」设置（isFilterInvalidBookmarks）：对齐 legacy beforeFirstLoad/beforeNextLoad，
 *   失效作品 / user 缺失 / user.id==0 三条都在设置开启时才过滤——注意与插画侧不同，legacy
 *   [ceui.lisa.core.Mapper] 的小说分支**不**恒过滤 !visible（只有 Illust 分支才过滤），
 *   所以这里不能把它提到设置外面，否则设置关着的用户会看不到本来能看到的收藏；
 * - 无 toolbar 菜单：legacy FragmentLikeNovel 用的是裸 NAdapter（不是插画侧的
 *   IAdapterWithStar），既没有「收藏到精华」菜单，也没有「收藏页隐藏收藏按钮」那套爱心门控。
 */
class LikeNovelFeedFragment : NovelFeedFragment() {

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
    private val filterViewModel: LikeNovelFilterViewModel by viewModels()

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
            Client.appApi.getUserBookmarkedNovels(
                userId, starType, filter.tag.takeIf { tag -> tag.isNotEmpty() }
            )
        }) { resp, _ -> mapLikeNovelPage(resp.displayList) }
    }

    // 收藏 Tab 内嵌 UserActivityV3(无底栏)时补底部手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    // (裸 feed 形态才生效,不会重复套)。
    override val applyBottomSafeInset: Boolean = true

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
            binding.toolbarTitle.setText(R.string.string_192) // 小说收藏
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(filterReceiver, IntentFilter(Params.FILTER_NOVEL))
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(filterReceiver)
        super.onDestroyView()
    }

    companion object {
        /** [initialTag] 初始按标签过滤（同义词词典管理页跳转用，issue #904），null/空 = 不过滤。 */
        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userID: Long,
            starType: String,
            showToolbar: Boolean = false,
            initialTag: String? = null,
        ): LikeNovelFeedFragment {
            return LikeNovelFeedFragment().apply {
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
        private fun mapLikeNovelPage(novels: List<Novel>): List<FeedItem> {
            // 「过滤无效收藏」设置：对齐 legacy beforeFirstLoad —— 失效作品 / 作者缺失 / 作者
            // id 为 0 的残缺条目一起挡，且都只在设置开启时才挡（小说侧的通用过滤链不含 visible，
            // 见类 KDoc）。visible 用 `== false` 而不是 `!= true`：legacy Novel.visible 是
            // 原始 boolean，字段缺失会默认 false 把整页清空；字段缺失不是「作品失效」的证据，
            // 这里只认服务端明确说的 false（字段照常返回时两者等价）。
            val filterInvalid = Shaft.sSettings.isFilterInvalidBookmarks
            return novels.mapNotNull { novel ->
                if (filterInvalid &&
                    (novel.visible == false || novel.user == null || novel.user?.id == 0L)
                ) {
                    return@mapNotNull null
                }
                // 收藏夹让步「小说自动屏蔽」（issue #743）：字数/超长 tag 阈值是冲着发现面上的
                // 刷屏广告去的，不该把用户自己收藏过的短文从藏书里抹掉。屏蔽 tag/画师/作品 ID
                // 和 R18 过滤照旧生效——那几条是用户对特定对象的显式表态。
                NovelFeedItem.of(novel, skipSpamFilter = true)
            }
        }
    }
}

/** 标签过滤条件（数据归 VM 约定：旋转存活，feed source 直接读它）。空串 = 不过滤。 */
class LikeNovelFilterViewModel : ViewModel() {
    var tag: String = ""
}
