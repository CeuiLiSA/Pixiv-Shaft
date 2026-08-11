package ceui.pixiv.ui.muted

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.MuteEntity
import ceui.lisa.databinding.RecyViewHistoryBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
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
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.common.MutedWorkStore
import ceui.pixiv.ui.common.NovelMuteStore
import ceui.pixiv.ui.common.tryOpenNovelReaderDirect
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.RequestOptions
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MuteEntity.type：1=插画/漫画，2=小说（0=标签、3=用户不进本页）。 */
private const val TYPE_ILLUST = 1
private const val TYPE_NOVEL = 2

/** 本页的行对应哪份内存名单（[MutedWorkStore]）；两种 type 之外的行不该出现在本页。 */
private fun muteStoreFor(type: Int): MutedWorkStore? = when (type) {
    TYPE_ILLUST -> IllustMuteStore
    TYPE_NOVEL -> NovelMuteStore
    else -> null
}

/**
 * 「屏蔽作品」列表页（feeds 框架版，替代 legacy FragmentMutedObjects + MuteWorksAdapter）。
 *
 * 是「屏蔽记录」FragmentViewPager 的第 3 个 tab；toolbar 归宿主 pager，本页用裸 fragment_feed、
 * 不自带 toolbar（默认无参构造 + LinearLayoutManager）。宿主把菜单点击经
 * `((Toolbar.OnMenuItemClickListener) currentFragment).onMenuItemClick(item)` 转发过来，故必须
 * implements 该接口；本 tab 的删除是逐行的（点删除按钮 → 弹窗确认 → 删单条），没有「一键清空」，
 * 对齐 legacy 返回 `false`（不消费菜单事件，交回宿主）。
 *
 * 数据是本地 tag_mute_table 里 type 1+2 的全量单页（[MutedObjectsFeedSource]），无翻页。
 */
class MutedObjectsFeedFragment : FeedFragment(), Toolbar.OnMenuItemClickListener {

    override val feedViewModel by feedViewModels {
        // 零捕获：source 无参，DB 走 application context。
        MutedObjectsFeedSource()
    }

    /** 插画封面方图边长（对齐 legacy MuteWorksAdapter：半屏宽方图）。 */
    private val illustImageSize by lazy {
        val res = requireContext().resources
        (res.displayMetrics.widthPixels - res.getDimensionPixelSize(R.dimen.four_dp)) / 2
    }

    /** 小说封面宽（legacy 固定 110dp，高保持 match_parent）。 */
    private val novelImageSize by lazy { DensityUtil.dp2px(110.0f) }

    /** 记录时间格式，对齐 legacy 的 mTime（R.string.string_350 = "MM月dd日 HH:mm"）。 */
    private val timeFormat by lazy {
        SimpleDateFormat(getString(R.string.string_350), Locale.getDefault())
    }

    /**
     * 封面 Glide 请求管理器，建一次复用（对齐 FeatureFeedFragment.featureGlide）：bind 加载 /
     * recycle 清理都走它，避免每次 bind 递归遍历 fragment 树找承载 fragment。
     */
    private val objectGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(objectGlide)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(mutedObjectRenderer())
    }

    /** 12dp 行间距，对齐 legacy ListFragment.verticalRecyclerView 的 LinearItemDecoration。 */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    /** 删除是逐行的、无一键清空——菜单事件不消费（对齐 legacy 的 return false）。 */
    override fun onMenuItemClick(item: MenuItem): Boolean = false

    /**
     * 屏蔽作品卡（recy_view_history）：复刻 MuteWorksAdapter 的 bind —— 插画封面高斯模糊、小说封面
     * 直出，标题 / 作者 / 时间 / 右下角标。删除按钮弹确认框（QMUIDialog）；小说卡整卡点开小说详情
     *（对齐 legacy itemView 直开 TemplateActivity），插画卡整卡无跳转（legacy 的 viewType 0 空实现）。
     */
    private fun mutedObjectRenderer() = feedRenderer<MutedObjectFeedItem, RecyViewHistoryBinding>(
        inflate = RecyViewHistoryBinding::inflate,
        create = { cell ->
            // onCreate 只注册一次，用 cell.item 取「点击那一刻」绑定的条目。
            cell.binding.deleteItem.setOnClickListener { confirmDelete(cell.item.entity) }
            cell.binding.root.setOnClickListener {
                val item = cell.item
                if (item.entity.type == TYPE_NOVEL) openNovelDetail(item.novel)
            }
        },
        recycle = { cell -> objectGlide.clear(cell.binding.illustImage) },
    ) { cell -> bindMutedObject(cell) }

    private fun bindMutedObject(cell: FeedCell<MutedObjectFeedItem, RecyViewHistoryBinding>) {
        val b = cell.binding
        val item = cell.item
        val entity = item.entity

        b.time.text = timeFormat.format(Date(entity.searchTime))

        when (entity.type) {
            TYPE_ILLUST -> {
                val illust = item.illust
                if (illust == null) {
                    clearCard(b)
                    return
                }
                b.illustImage.updateLayoutParams {
                    width = illustImageSize
                    height = illustImageSize
                }
                objectGlide
                    .load(GlideUtil.getMediumImg(illust))
                    .apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3)))
                    .placeholder(R.color.light_bg)
                    .into(b.illustImage)
                b.title.text = titleOr(illust.title, illust.id)
                b.author.text = authorLine(illust.user?.name)
                when {
                    illust.isGif -> {
                        b.pSize.isVisible = true
                        b.pSize.text = "GIF"
                    }

                    // <=0 是「记录里没有页数」——老 MMKV 名单迁过来的 id 壳行就是这样，
                    // 不加这条会挂一个「0P」角标出来。1P 本来就不显示。
                    illust.page_count <= 1 -> b.pSize.isVisible = false
                    else -> {
                        b.pSize.isVisible = true
                        b.pSize.text = String.format(Locale.getDefault(), "%dP", illust.page_count)
                    }
                }
            }

            TYPE_NOVEL -> {
                val novel = item.novel
                if (novel == null) {
                    clearCard(b)
                    return
                }
                // legacy 只改宽、留高 match_parent。
                b.illustImage.updateLayoutParams { width = novelImageSize }
                objectGlide
                    .load(GlideUtil.getUrl(novel.image_urls?.medium))
                    .placeholder(R.color.light_bg)
                    .into(b.illustImage)
                b.title.text = titleOr(novel.title, novel.id)
                b.author.text = authorLine(novel.user?.name)
                b.pSize.isVisible = true
                b.pSize.text = "小说"
            }

            else -> clearCard(b)
        }
    }

    /**
     * 记录里只有 id、没有标题的行退回 `#id`。
     *
     * 这类行来自老 MMKV 遮罩名单的一次性迁移（见 [MutedWorkStore]）：那边只存过作品 id，
     * 标题封面作者都补不回来。留个 `#id` 至少让这一条看得见、点得到删除按钮。
     */
    private fun titleOr(title: String?, id: Int): CharSequence =
        title?.takeIf { it.isNotBlank() } ?: "#$id"

    /** 同上：作者名缺失就整行留空，不显示一个孤零零的 "by: "。 */
    private fun authorLine(name: String?): CharSequence =
        name?.takeIf { it.isNotBlank() }?.let { "by: $it" } ?: ""

    /** 脏 JSON 兜底（app 自写数据几乎不会命中）：清掉复用残留，避免显示上一条内容。 */
    private fun clearCard(b: RecyViewHistoryBinding) {
        objectGlide.clear(b.illustImage)
        b.illustImage.setImageDrawable(null)
        b.title.text = ""
        b.author.text = ""
        b.pSize.isVisible = false
    }

    /** 小说卡整卡点击：直开小说详情（用条目里预解析好的 bean，对齐 legacy MuteWorksAdapter 的 itemView 点击）。 */
    private fun openNovelDetail(novel: NovelBean?) {
        val ctx = context ?: return
        val target = novel ?: return
        if (ctx.tryOpenNovelReaderDirect(target.id.toLong())) return
        val intent = Intent(ctx, TemplateActivity::class.java).apply {
            putExtra(Params.CONTENT, target)
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra("hideStatusBar", true)
        }
        ctx.startActivity(intent)
    }

    /**
     * 删除确认框：QMUIDialog + QMUISkinManager（日夜皮肤），对齐 legacy 的标题 / 文案 / 按钮。
     * 确认后 IO 线程删 DB、再从列表移除该条、toast。
     *
     * 用 Fragment 级 [lifecycleScope]（不是 viewLifecycleOwner）：弹窗锚在 Activity 上，可能在
     * 视图销毁后才回调；DB 删除应照常完成，[feedViewModel] 也比视图活得久（removeItems 安全）。
     * ctx 提前抓 applicationContext，回调触发时 fragment 可能已 detach。
     */
    private fun confirmDelete(entity: MuteEntity) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                lifecycleScope.launch {
                    // 删库和内存名单一并交给 store：它的 false 方向是无条件删的，正是给这种
                    // 「库里有行、内存未必有」的入口准备的。绝不能自己 deleteMuteEntity ——
                    // 那会绕开 store 的单线程写队列，和排队中的 insert 抢顺序，把刚删的行复活
                    //（见 MutedWorkStore 类注释）。顺带把列表卡片的遮罩判定同步掉，否则回到
                    // 瀑布流那张卡还糊着。
                    val store = muteStoreFor(entity.type)
                    if (store != null) {
                        store.setMuted(entity.id.toLong(), false)
                    } else {
                        // 理论上到不了（本页只有 type 1/2），留着别让脏行删不掉
                        withContext(Dispatchers.IO) {
                            AppDatabase.getAppDatabase(appContext).searchDao()
                                .deleteMuteEntity(entity)
                        }
                    }
                    feedViewModel.removeItems { it is MutedObjectFeedItem && it.sameEntity(entity) }
                    Common.showToast(R.string.string_220)
                }
            }
            .show()
    }
}

/**
 * 单个屏蔽作品条目。
 *
 * [MuteEntity] 主键是复合的 `{id, type}`（type 1=插画/漫画、2=小说），单用 id 不唯一——插画 id 与
 * 小说 id 可能撞号，故 [feedKey] 取 `(id, type)` 复合值，守住 DiffUtil「同类条目身份唯一」不变量。
 */
data class MutedObjectFeedItem(
    val entity: MuteEntity,
    /** 预解析的插画 bean（type==1 时非空，坏 JSON 为 null）——不在 bind 时 per-bind 解析。 */
    val illust: IllustsBean?,
    /** 预解析的小说 bean（type==2 时非空，坏 JSON 为 null）。 */
    val novel: NovelBean?,
) : FeedItem {

    override val feedKey: Any = entity.id to entity.type

    /** 是否指向同一条 DB 记录（复合主键比对），删除时按此匹配。 */
    fun sameEntity(other: MuteEntity): Boolean =
        entity.id == other.id && entity.type == other.type
}

/**
 * 屏蔽作品数据源：tag_mute_table 中 type 1(插画/漫画)+2(小说) 全量单页，无翻页
 *（nextCursor 恒为 null，对齐 legacy LocalRepo.next() 返回 null）。
 *
 * 零 Fragment 捕获：无参构造，DB 走 [IllustNovelFilter.getMutedWorks]（其内部用 [Shaft.getContext]
 * 的 application context）。IO 线程查库 + 建条目，对齐模板页 [ceui.pixiv.ui.watchlater.WatchLaterFeedSource]。
 */
class MutedObjectsFeedSource : FeedSource<Int> {

    override suspend fun load(cursor: Int?): FeedPage<Int> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            IllustNovelFilter.getMutedWorks().map { entity ->
                // tagJson 反序列化在 IO 线程一次做完（本仓「bean 预解析别 per-bind」约定），
                // 坏数据 runCatching 跳过为 null，卡片走 clearCard 兜底。
                val illust = if (entity.type == TYPE_ILLUST) {
                    runCatching {
                        Shaft.sGson.fromJson(entity.tagJson, IllustsBean::class.java)
                    }.getOrNull()
                } else {
                    null
                }
                val novel = if (entity.type == TYPE_NOVEL) {
                    runCatching {
                        Shaft.sGson.fromJson(entity.tagJson, NovelBean::class.java)
                    }.getOrNull()
                } else {
                    null
                }
                MutedObjectFeedItem(entity, illust, novel)
            }
        }
        return FeedPage(items, null)
    }
}
