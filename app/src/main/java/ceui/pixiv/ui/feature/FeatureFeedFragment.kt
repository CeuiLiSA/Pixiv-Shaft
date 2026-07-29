package ceui.pixiv.ui.feature

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.CellFeatureV3Binding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.MangaSeriesItem
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
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
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.google.gson.reflect.TypeToken
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** 本地精华列表的分页大小，对齐 legacy ListFragment.PAGE_SIZE（20）。 */
private const val PAGE_SIZE = 20

/**
 * 「精华列表」页（feeds 框架版，替代 legacy FragmentFeature + LocalListFragment + FeatureAdapter）。
 *
 * 宿主是 TemplateActivity 的独立页（自带 toolbar），数据是 feature_table 的本地 Room 记录，
 * offset 翻页。逐条对齐 legacy：
 * - toolbar 走 feeds 独立页统一的 fragment_toolbar_feed（webview 5 件套），标题「精华列表」，
 *   overflow 挂本页专属的 R.menu.feature_list（只有清空一项，见该文件注释）；
 * - 卡片走 cell_feature_v3（V3 + MD3-E，见该布局注释）：最多 3 张预览图（先插画封面、不足 3 张
 *   再拿漫画系列封面补位）+ 类型 chip + 添加时间 + 删除按钮；点卡片按 dataType 分发到对应
 *   二级页，点删除弹确认框删单条；
 * - LinearLayoutManager + 12dp 竖向间距（对齐 legacy verticalRecyclerView）。
 */
class FeatureFeedFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        // 零捕获：source 无参，DB 走 application context。
        FeatureFeedSource()
    }

    /**
     * 3 张预览图的 Glide 请求管理器，建一次复用（对齐 [ceui.pixiv.ui.common.UserFeedFragment] 的
     * userGlide）。`Glide.with(Fragment)` 直接命中同一个 RequestManager，避免每张卡 recycle/bind
     * 都递归遍历 fragment 树找承载 fragment。
     */
    private val featureGlide: RequestManager by lazy { Glide.with(this) }

    /** 卡片「yyyy年MM月dd日 HH:mm 添加」的日期格式化器（对齐 legacy FeatureAdapter，只在主线程绑卡用）。 */
    private val dateFormat by lazy {
        SimpleDateFormat(getString(R.string.string_351), Locale.getDefault())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(featureGlide)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_249)
        binding.toolbar.inflateMenu(R.menu.feature_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                confirmDeleteAll()
                true
            } else {
                false
            }
        }
    }

    override val emptyStateText: CharSequence
        get() = getString(R.string.feature_empty_hint)

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(featureRenderer())
    }

    private fun featureRenderer() = feedRenderer<FeatureFeedItem, CellFeatureV3Binding>(
        inflate = CellFeatureV3Binding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openFeature(cell.item.entity) }
            cell.binding.deleteItem.setOnClick { confirmDeleteSingle(cell.item.entity) }
        },
        recycle = { cell ->
            listOf(cell.binding.userShowOne, cell.binding.userShowTwo, cell.binding.userShowThree)
                .forEach { featureGlide.clear(it) }
        },
    ) { cell -> bindFeature(cell) }

    private fun bindFeature(cell: FeedCell<FeatureFeedItem, CellFeatureV3Binding>) {
        val b = cell.binding
        val entity = cell.item.entity
        b.starSize.text = dateFormat.format(Date(entity.dateTime))
        b.illustCount.text = entity.dataType

        // 先插画封面，不足 3 张再拿漫画系列封面补位（逐条对齐 legacy FeatureAdapter）。
        val shows: MutableList<Serializable> =
            ArrayList(entity.allIllust.subList(0, min(3, entity.allIllust.size)))
        if (shows.size < 3) {
            shows.addAll(
                entity.allMangaSeries.subList(0, min(3 - shows.size, entity.allMangaSeries.size))
            )
        }
        // 空槽整块 GONE：三个槽都是 weight=1，藏掉多余的槽会让剩下的自动摊开填满整行，
        // 不会像 legacy 那样在只有 1～2 张封面时留下灰色空位。
        listOf(b.userShowOne, b.userShowTwo, b.userShowThree)
            .forEachIndexed { index, iv ->
                iv.isVisible = index < shows.size
                val url = when (val show = shows.getOrNull(index)) {
                    is IllustsBean -> GlideUtil.getMediumImg(show)
                    is MangaSeriesItem -> GlideUtil.getUrl(show.cover_image_urls.medium)
                    else -> null
                }
                if (url == null) {
                    featureGlide.clear(iv)
                } else {
                    featureGlide.load(url).placeholder(R.color.v3_surface_2).into(iv)
                }
            }
    }

    /** 点卡片：按 dataType 分发到对应二级页（对齐 legacy 的 viewType==0 分支）。 */
    private fun openFeature(entity: FeatureEntity) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, entity.dataType)
        intent.putExtra(Params.USER_ID, entity.userID)
        intent.putExtra(Params.ILLUST_ID, entity.illustID)
        intent.putExtra(Params.ILLUST_TITLE, entity.illustTitle)
        intent.putExtra(Params.MANGA_SERIES_ID, entity.seriesId)
        startActivity(intent)
    }

    /** 点删除按钮：确认后删单条（对齐 legacy 的 viewType==1 分支）。 */
    private fun confirmDeleteSingle(entity: FeatureEntity) {
        val ctx = context ?: return
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_252)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, QMUIDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                val appCtx = ctx.applicationContext
                // Fragment 级 lifecycleScope + application context：弹窗可能在视图销毁后才回调，
                // 删除应照常完成（feedViewModel 比视图活得久，摘条 / 回退游标都安全）。
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(appCtx).downloadDao().deleteFeature(entity)
                    }
                    feedViewModel.removeItems {
                        it is FeatureFeedItem && it.entity.uuid == entity.uuid
                    }
                    // offset 游标是绝对 DB 偏移，删除只改内存列表不动游标 → 下一页会从旧 offset 起读、
                    // 跳过一条。回退到「当前列表条数」，对齐 legacy 每页用 allItems.size() 当 offset 的自愈。
                    feedViewModel.adoptCursor(feedViewModel.uiState.value.items.size)
                    Common.showToast(R.string.string_220)
                }
            }
            .show()
    }

    /** toolbar 清空全部：空列表先 toast 拦截，否则确认后 deleteAllFeature() + refresh 回空态。 */
    private fun confirmDeleteAll() {
        val ctx = context ?: return
        if (feedViewModel.uiState.value.items.isEmpty()) {
            Common.showToast(R.string.string_254)
            return
        }
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_253)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.string_141, QMUIDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                val appCtx = ctx.applicationContext
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(appCtx).downloadDao().deleteAllFeature()
                    }
                    // refresh 重拉第一页（空）→ 框架自动亮空态，无需手动清列表。
                    feedViewModel.refresh()
                    Common.showToast(R.string.string_220)
                }
            }
            .show()
    }
}

/**
 * 精华列表条目：持一条 [FeatureEntity]。身份 = (类型, uuid)，uuid 是 feature_table 主键。
 *
 * 不做内容增量比较（无收藏 / 点赞类局部态）：每次刷新都是新解析出的实例，DiffUtil 全量重绑即可，
 * 与 legacy「整表 notifyItemRangeInserted」语义一致。
 */
class FeatureFeedItem(val entity: FeatureEntity) : FeedItem {
    override val feedKey: Any get() = entity.uuid
}

/**
 * 精华列表数据源：feature_table 的 offset 翻页（cursor = Room offset，对齐 legacy 的
 * `getFeatureList(PAGE_SIZE, allItems.size)`）。
 *
 * **illustJson → allIllust / allMangaSeries 的 gson 反序列化在这里（IO 线程）一次做完**，
 * 绝不留到绑卡时 per-bind 解析（本仓硬性性能约定）。dataType == "漫画系列作品" 解成
 * List<MangaSeriesItem>，否则解成 List<IllustsBean>——逐条对齐 legacy FragmentFeature.handleFeatures。
 *
 * 零 Fragment 捕获：无参构造，DB 走 [Shaft.getContext] 的 application context。
 */
class FeatureFeedSource : FeedSource<Int> {

    override suspend fun load(cursor: Int?): FeedPage<Int> {
        val offset = cursor ?: 0
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            val page = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
                .getFeatureList(PAGE_SIZE, offset)
            page.forEach { entity ->
                if (entity.illustJson.isNotEmpty()) {
                    if (entity.dataType == "漫画系列作品") {
                        entity.allMangaSeries = Shaft.sGson.fromJson(
                            entity.illustJson,
                            object : TypeToken<List<MangaSeriesItem>>() {}.type,
                        )
                    } else {
                        entity.allIllust = Shaft.sGson.fromJson(
                            entity.illustJson,
                            object : TypeToken<List<IllustsBean>>() {}.type,
                        )
                    }
                }
            }
            page.map { FeatureFeedItem(it) }
        }
        // 不足一页即到底；否则下一页从 offset + 本页条数 继续（对齐 legacy 用 allItems.size 作 offset）。
        val nextCursor = if (items.size < PAGE_SIZE) null else offset + items.size
        return FeedPage(items, nextCursor)
    }
}
