package ceui.pixiv.ui.dynamic

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemTimelineGridImageBinding
import ceui.lisa.databinding.RecyTimelineIllustBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.loxia.DateParse
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation
import java.util.Locale

/** 单图区的高宽比钳制（对齐 legacy TimelineAdapter，比瀑布流卡窄一档）。 */
private const val MIN_HEIGHT_RATIO = 0.6f
private const val MAX_HEIGHT_RATIO = 1.5f

/** 卡片左右各 16dp margin（recy_timeline_illust 里 CardView 的 layout_marginHorizontal）。 */
private const val CARD_HORIZONTAL_MARGIN_DP = 32f

/** 宫格格子间距（对齐 legacy 的 cellSize 算法）。 */
private const val GRID_GAP_DP = 2f

/** 屏蔽态的 Glide 模糊参数，与瀑布流卡逐字一致（IllustStaggerRenderer 的 25/3）。 */
private const val SPOILER_BLUR_RADIUS = 25
private const val SPOILER_BLUR_SAMPLING = 3

/** 单图区的 Glide 请求去重 key（存在 ImageView.tag 上）：url + 请求尺寸 + 是否模糊。 */
private data class TimelineImageKey(
    val cacheKey: String?,
    val width: Int,
    val height: Int,
    val blurred: Boolean,
)

/** 宫格格子的同款 key：格子是正方形，尺寸只需一维，另带页码区分同卡的不同格。 */
private data class TimelineGridImageKey(
    val cacheKey: String?,
    val cellSize: Int,
    val position: Int,
    val blurred: Boolean,
)

/**
 * 「动态」页时间线模式的大卡（recy_timeline_illust，与 legacy TimelineAdapter 同一张布局、
 * 同一套语义）：头像 + 画师名 + 相对时间 / 标题 / 大图或宫格 / 浏览·收藏数 pill / 多图角标。
 *
 * 点击整卡开详情。卡上**没有**收藏爱心、也没有长按卡片菜单——对齐 legacy TimelineAdapter
 * （瀑布流模式的卡才有，那套在 [ceui.pixiv.ui.common.IllustFeedFragment.staggerIllustRenderer]）。
 *
 * 「屏蔽此作品」在这张卡上同样是**遮罩**：图（单图和宫格每一格）走 Glide 出模糊位图、标题藏起来，
 * 点一下是取消屏蔽（[onUnmute]）而不是开详情。本卡没有粒子层——布局里没有
 * SpoilerParticleView，单靠整块模糊 + 无标题已经足够表达「这条被你屏蔽了」。
 *
 * ⚠️ 这段打码不是可选装饰：[ceui.pixiv.ui.common.IllustFeedItem] 的过滤链**不再**按屏蔽记录
 * 删条目（那样就没法在卡上取消屏蔽了），所以被屏蔽的作品一定会走到这里。少了打码，屏蔽在
 * 时间线模式下等于没发生——原图整幅铺开，且本卡没有长按菜单，用户连撤销的入口都摸不到。
 */
fun timelineIllustRenderer(
    onClick: (IllustFeedItem) -> Unit,
    onUnmute: (IllustFeedItem) -> Unit,
): FeedRenderer<IllustFeedItem, RecyTimelineIllustBinding> {
    // 打码的卡：点一下先取消屏蔽、不开详情，再点才进详情。否则手一滑就把刚遮住的东西整幅铺开了。
    // 本卡没有长按菜单，这也是时间线模式下唯一的撤销入口。
    val handleTap: (IllustFeedItem) -> Unit = { item ->
        if (IllustMuteStore.isMuted(item.illust.id)) onUnmute(item) else onClick(item)
    }
    return feedRenderer(
        inflate = RecyTimelineIllustBinding::inflate,
        create = { cell ->
            cell.binding.card.setOnClick { handleTap(cell.item) }
            // 宫格的 LayoutManager / Adapter 建一次就跟着 ViewHolder 走。legacy 是每次 bind 都
            // new 一份 GridLayoutManager + GridImageAdapter 塞进内嵌 RecyclerView——fling 时每张
            // 多图卡都要重建一遍适配器，纯浪费。这里只在 bind 时改 spanCount + 换数据。
            cell.binding.imageGrid.apply {
                layoutManager = GridLayoutManager(context, 2)
                // 格子点击等价于点整卡（对齐 legacy 的 gridClick）
                adapter = TimelineGridAdapter { handleTap(cell.item) }
            }
        },
        recycle = { cell ->
            cell.binding.illustImage.clearGlideOnRecycle()
            cell.binding.userIcon.clearGlideOnRecycle()
            // tag 是「这张 ImageView 上已经加载了什么」的凭证，清图时必须一并清，
            // 否则复用到恰好同 url 同尺寸的条目时会以为图还在而漏加载
            cell.binding.illustImage.tag = null
            cell.binding.userIcon.tag = null
        },
    ) { cell -> bindTimelineCard(cell) }
}

private fun bindTimelineCard(cell: FeedCell<IllustFeedItem, RecyTimelineIllustBinding>) {
    val b = cell.binding
    val bean = cell.item.bean
    val ctx = b.root.context

    // 打码态 bind 时现读（条目本身不带这个状态，同瀑布流卡）；
    // 没被回收的卡由 IllustFeedFragment.observeMuteRevision 补一次 payload 重绑
    //（本 renderer 不认识那个 payload，会按框架约定退回全量绑定，也就是重跑这里）。
    val masked = IllustMuteStore.isMuted(cell.item.illust.id)

    val user = bean.user
    b.userName.text = user?.name ?: ""
    bindAvatar(b, user)
    b.postTime.text = DateParse.getTimeAgo(ctx, bean.create_date)

    // 标题也藏起来：图糊了但标题还写着，屏蔽就漏了一半
    val title = bean.title
    b.title.isVisible = !masked && !title.isNullOrEmpty()
    b.title.text = title ?: ""

    // 图区宽度 = 屏宽 - 卡片左右 margin。图区高度由代码算死（centerCrop 需要确定尺寸），
    // 所以下面每次加载都 override 成这个尺寸：请求宽高比恒等于展示宽高比，复用卡片不会
    // 因为解码时按上一次的旧尺寸裁位图而裁飞（同瀑布流卡，见 project_stagger_glide_override）。
    val imageWidth = ctx.resources.displayMetrics.widthPixels -
            DensityUtil.dp2px(CARD_HORIZONTAL_MARGIN_DP)

    val pages = bean.meta_pages
    val isMulti = bean.page_count > 1 && !pages.isNullOrEmpty()
    if (isMulti) {
        bindGrid(b, bean, pages!!.size, imageWidth, masked)
    } else {
        bindSingleImage(b, bean, imageWidth, masked)
    }

    b.viewCount.text = String.format(Locale.getDefault(), "%,d", bean.total_view)
    b.bookmarkCount.text = String.format(Locale.getDefault(), "%,d", bean.total_bookmarks)

    b.pageCountBadge.isVisible = bean.page_count > 1
    if (bean.page_count > 1) {
        b.pageCount.text = bean.page_count.toString()
    }
}

/**
 * 头像也按 tag 去重，理由同下面那张大图：本卡展示浏览/收藏数，刷新时这两个数几乎必变 →
 * 整卡全量重绑，而 `into()` 会先把当前 drawable 清成 null 再重新解码（GlideUrlChild 每次
 * 构造都带时间戳请求头，Glide 认不出「这张头像已经在显示」）——不去重的话每次下拉刷新
 * 满屏头像都要白一下再淡入。
 *
 * 无作者（[user] 为 null）时主动清干净：复用来的卡片可能还挂着上一条的头像。
 */
private fun bindAvatar(b: RecyTimelineIllustBinding, user: UserBean?) {
    val requestKey = user?.let { GlideUtil.getHead(it) }?.cacheKey
    if (b.userIcon.tag == requestKey) return
    b.userIcon.tag = requestKey
    if (user == null) {
        Glide.with(b.userIcon).clear(b.userIcon)
    } else {
        Glide.with(b.userIcon).load(GlideUtil.getHead(user)).into(b.userIcon)
    }
}

private fun bindSingleImage(
    b: RecyTimelineIllustBinding,
    bean: IllustsBean,
    imageWidth: Int,
    masked: Boolean,
) {
    b.illustImage.isVisible = true
    b.imageGrid.isVisible = false

    val ratio = if (bean.width > 0 && bean.height > 0) {
        (bean.height.toFloat() / bean.width.toFloat()).coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
    } else {
        1f
    }
    val imageHeight = (imageWidth * ratio).toInt()
    b.illustImage.updateLayoutParams {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = imageHeight
    }

    val url = GlideUtil.getLargeImage(bean)
    // GlideUrlChild 每次构造都带当前时间戳请求头，Glide 自己认不出「这张图已经在显示」；
    // 而本卡展示 total_view/total_bookmarks，刷新时这俩几乎必变 → 整卡全量重绑 → 图白闪一次。
    // 用「请求 URL(不含 headers 的 cacheKey) + 目标尺寸 + 是否模糊」当 tag，真没变就跳过重新
    // 加载（同瀑布流卡）。带上 blurred 这一维是必须的：屏蔽态切换时 url 和尺寸都没变，少了它
    // key 相等就会跳过重新加载，图永远不糊 / 不清。
    val requestKey = TimelineImageKey(url?.cacheKey, imageWidth, imageHeight, masked)
    if (b.illustImage.tag != requestKey) {
        b.illustImage.tag = requestKey
        var request = Glide.with(b.illustImage)
            .load(url)
            .override(imageWidth, imageHeight)
        if (masked) {
            request = request.apply(
                bitmapTransform(BlurTransformation(SPOILER_BLUR_RADIUS, SPOILER_BLUR_SAMPLING))
            )
        }
        request
            .placeholder(R.color.v3_surface_2)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(b.illustImage)
    }
}

private fun bindGrid(
    b: RecyTimelineIllustBinding,
    bean: IllustsBean,
    totalPages: Int,
    imageWidth: Int,
    masked: Boolean,
) {
    b.illustImage.isVisible = false
    b.imageGrid.isVisible = true
    // 单图 tag 不能留着：这张卡下次复用回单图模式时会被误判成「图还在」而漏加载
    Glide.with(b.illustImage).clear(b.illustImage)
    b.illustImage.tag = null

    // ≤4 张走 2 列（最多 4 格），更多走 3 列（最多 9 格），超出部分在最后一格叠「+N」（对齐 legacy）
    val spanCount = if (totalPages <= 4) 2 else 3
    val maxShow = if (spanCount == 2) 4 else 9
    val showCount = minOf(totalPages, maxShow)
    val hasMore = totalPages > maxShow
    val cellSize = (imageWidth - (spanCount - 1) * DensityUtil.dp2px(GRID_GAP_DP)) / spanCount

    (b.imageGrid.layoutManager as GridLayoutManager).spanCount = spanCount
    (b.imageGrid.adapter as TimelineGridAdapter)
        .submit(bean, showCount, cellSize, hasMore, totalPages - maxShow, masked)
}

/**
 * 时间线大卡里的多图宫格。数据整批替换（一张卡的图只有几张），所以 notifyDataSetChanged
 * 就是最诚实的表达；每个格子按「url + 尺寸」tag 去重，外层卡因为浏览量变化重绑时不会重发请求。
 */
private class TimelineGridAdapter(
    private val onCellClick: () -> Unit,
) : RecyclerView.Adapter<TimelineGridAdapter.VH>() {

    private var illust: IllustsBean? = null
    private var showCount = 0
    private var cellSize = 0
    private var hasMore = false
    private var remaining = 0
    private var masked = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(
        illust: IllustsBean,
        showCount: Int,
        cellSize: Int,
        hasMore: Boolean,
        remaining: Int,
        masked: Boolean,
    ) {
        this.illust = illust
        this.showCount = showCount
        this.cellSize = cellSize
        this.hasMore = hasMore
        this.remaining = remaining
        this.masked = masked
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = showCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTimelineGridImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        binding.root.setOnClick { onCellClick() }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bean = illust ?: return
        holder.itemView.updateLayoutParams { height = cellSize }

        val url = GlideUtil.getLargeImage(bean, position)
        // masked 进 key：屏蔽态切换时 url 和格子尺寸都没变，少了它就跳过重新加载、图不糊
        val requestKey = TimelineGridImageKey(url?.cacheKey, cellSize, position, masked)
        if (holder.binding.gridImage.tag != requestKey) {
            holder.binding.gridImage.tag = requestKey
            var request = Glide.with(holder.binding.gridImage)
                .load(url)
                .override(cellSize, cellSize)
            if (masked) {
                request = request.apply(
                    bitmapTransform(BlurTransformation(SPOILER_BLUR_RADIUS, SPOILER_BLUR_SAMPLING))
                )
            }
            request
                .placeholder(R.color.v3_surface_2)
                .into(holder.binding.gridImage)
        }

        val isLast = position == showCount - 1
        holder.binding.overlay.isVisible = isLast && hasMore
        if (isLast && hasMore) {
            holder.binding.moreCount.text = String.format(Locale.getDefault(), "+%d", remaining)
        }
    }

    class VH(val binding: ItemTimelineGridImageBinding) : RecyclerView.ViewHolder(binding.root)
}
