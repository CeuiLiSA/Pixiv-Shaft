package ceui.pixiv.ui.user

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.databinding.RecyMangaSeriesBinding
import ceui.lisa.http.Retro
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.model.ListMangaSeries
import ceui.lisa.models.MangaSeriesItem
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「漫画系列作品」列表页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentMangaSeries] +
 * MangaSeriesAdapter）。某画师的漫画系列总览，toolbar 标题 [R.string.string_230]。
 *
 * 卡形与交互 1:1 复刻 legacy MangaSeriesAdapter 的 recy_manga_series（databinding 生成的
 * [RecyMangaSeriesBinding] 天然实现 ViewBinding，直接喂 [feedRenderer]，对齐 NovelMarkersFeedFragment
 * 复用 recy_novel_markers 的做法）：封面 + 系列标题 + 话数，整卡点击进「漫画系列详情」。
 *
 * toolbar 菜单沿用 legacy 的 [R.menu.local_save]，只处理 action_bookmark：把当前整份列表收藏成
 * 一条精华（FeatureEntity），DB 写入切 IO（对齐记忆里的「DB 写 IO」约束）。
 */
class UserMangaSeriesFeedFragment : FeedFragment() {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    /** toolbar 收藏动作要用的画师 id（fragment 作用域读 arguments，非零捕获路径，直接读安全）。 */
    private val userID: Long
        get() = Params.getUserId(requireArguments())

    /**
     * 两种形态,对齐 [UserNovelSeriesFeedFragment]:
     * - 独立(TemplateActivity「漫画系列作品」,showToolbar=true,legacy 默认):自带返回箭头 + 标题 +
     *   收藏成精华菜单;
     * - 内嵌(UserActivityV3「漫画系列」Tab,showToolbar=false):去掉 toolbar,否则 Tab 里会多顶一条头。
     */
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG, true)
    }

    // 内嵌 UserActivityV3 tab(无底栏)时,列表底部补手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    override val applyBottomSafeInset: Boolean = true

    // autoLoad = false：本页会作为 UserActivityV3 的「漫画系列」tab 挂进 ViewPager2，而 pager 会在
    // 用户滑到相邻位置时就把 fragment 建出来。默认的 autoLoad 会在 onViewCreated 首次访问
    // feedViewModel 时（VM 的 init）直接发请求——tab 从没被打开也请求了。同宿主的漫画 / 小说 /
    // 小说系列 tab 全是 autoLoad=false，此处对齐；首屏由 FeedFragment.onResume 的 ensureLoaded 补，
    // 独立 TemplateActivity 形态（一进来就 RESUMED）不受影响。
    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：只把 userID(Long) 读成局部 val 按值传给 source，不碰 Fragment / View。
        val uid = Params.getUserId(requireArguments())
        userMangaSeriesFeedSource(uid)
    }

    /**
     * 封面 Glide 请求管理器，建一次复用（对齐 NovelMarkersFeedFragment.rowGlide）：
     * bind 加载 / recycle 清理都走它，避免每处 `Glide.with(view)` 递归找承载 fragment。
     */
    private val rowGlide: RequestManager by lazy { Glide.with(this) }

    // showToolbar 是运行时参数,系统重建只走无参构造,不能靠构造器传 contentLayoutId,
    // 改在这里按参数选骨架(两张布局都带同结构的 feed_root)。对齐 UserNovelSeriesFeedFragment。
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
        pinHostGlide(rowGlide)
        // 内嵌 tab 无 toolbar:跳过标题 / 返回箭头 / 收藏成精华菜单(它们都挂在 toolbar 上)。
        if (!showToolbar) return
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_230)
        binding.toolbar.inflateMenu(R.menu.local_save)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_bookmark) {
                bookmarkAsFeature()
                true
            } else {
                false
            }
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(mangaSeriesRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐 legacy 的 LinearItemDecorationNoLRTB(dp2px(1f))：只在条目之间留 1dp 顶距。
        listView.addItemDecoration(LinearItemDecorationNoLRTB(1.ppppx))
    }

    private fun mangaSeriesRenderer() = feedRenderer<MangaSeriesFeedItem, RecyMangaSeriesBinding>(
        inflate = RecyMangaSeriesBinding::inflate,
        // 点击监听只在 onCreate 注册一次，绑定时零 lambda 分配；点击那一刻读 cell.item 拿当下条目。
        create = { cell ->
            cell.binding.root.setOnClick { openSeries(cell.item.series) }
        },
        recycle = { cell ->
            rowGlide.clear(cell.binding.imageView)
        },
    ) { cell -> bindRow(cell) }

    private fun bindRow(cell: FeedCell<MangaSeriesFeedItem, RecyMangaSeriesBinding>) {
        val b = cell.binding
        val series = cell.item.series
        b.seriesTitle.text = "#%s".format(series.title)
        b.seriesSize.text = "共%d话".format(series.series_work_count)
        // 与 legacy 一致：封面 medium 为空时不发请求（recycle 已清掉上一张，不会串图）。
        if (!TextUtils.isEmpty(series.cover_image_urls.medium)) {
            rowGlide.load(GlideUtil.getUrl(series.cover_image_urls.medium)).into(b.imageView)
        }
    }

    /** 整卡点击：进「漫画系列详情」，携带系列 id（与 legacy itemView 点击一字不差）。 */
    private fun openSeries(series: MangaSeriesItem) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
        intent.putExtra(Params.MANGA_SERIES_ID, series.id)
        startActivity(intent)
    }

    /**
     * 把当前整份列表收藏成一条精华（FeatureEntity），复刻 legacy action_bookmark：
     * uuid = userID + "漫画系列作品"，dataType 同名，illustJson 走 [Common.cutToJson]（仅前 5 条）。
     * DB 写入切 IO，成功后主线程 toast。
     */
    private fun bookmarkAsFeature() {
        val ctx = context ?: return
        val uid = userID
        val allItems = feedViewModel.uiState.value.items
            .filterIsInstance<MangaSeriesFeedItem>()
            .map { it.series }
        val entity = FeatureEntity().apply {
            uuid = uid.toString() + "漫画系列作品"
            dataType = "漫画系列作品"
            illustJson = Common.cutToJson(allItems)
            userID = uid
            dateTime = System.currentTimeMillis()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(ctx.applicationContext).downloadDao().insertFeature(entity)
            }
            Common.showToast(getString(R.string.series_bookmarked_feature))
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun newInstance(userID: Long, showToolbar: Boolean = true): UserMangaSeriesFeedFragment {
            return UserMangaSeriesFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userID)
                    putBoolean(Params.FLAG, showToolbar)
                }
            }
        }
    }
}

/**
 * 漫画系列条目：持 legacy [MangaSeriesItem]（网络下行的 illust_series_details 单条）。
 * feedKey 用系列 id（[MangaSeriesItem.getId] 返回 int，同类内唯一稳定）。
 */
class MangaSeriesFeedItem(val series: MangaSeriesItem) : FeedItem {
    override val feedKey: Any get() = series.id
}

/**
 * 漫画系列数据源：首页 getUserMangaSeries，翻页由 [PixivFeedSource] 按 next_url 重放。
 * 零 Fragment 捕获：只捕获一个 userID(Long)。
 */
private fun userMangaSeriesFeedSource(userID: Long): PixivFeedSource<ListMangaSeries> =
    pixivFeedSource(initialFetch = { Retro.getAppApi().getUserMangaSeries(userID) }) { resp, _ ->
        resp.displayList.map { MangaSeriesFeedItem(it) }
    }
