package ceui.pixiv.ui.common

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.pixiv.feeds.FeedAdapter
import kotlin.math.roundToInt

/**
 * 齐行布局的目标行高 = 列宽 × 1.4，即瀑布流里一张常见竖图卡的高度：「每行 2 列」时一行约两张竖图，
 * 和瀑布流的图一样大。直接拿列宽当行高时一行能塞三张竖图，图明显偏小。
 */
internal const val JUSTIFIED_ROW_HEIGHT_FACTOR = 1.4f

/**
 * 齐行布局（等高不等宽，#1214）：作品按宽高比排成左右齐边的行，同一行等高。
 *
 * 跨度数 = 列表内容宽度（px），一格就是 1px，GridLayoutManager 只负责把算好的像素宽排进行里。
 * 关键在于**不需要在绑定时知道行高**：一行里每张卡分到的内容宽 = 宽高比 × 行高，而卡内的
 * DynamicHeightImageView 本来就按「宽 × 高宽比」量高（与瀑布流同一个 [heightRatioOf]），
 * 量出来自然就是行高。所以卡片绑定逻辑与瀑布流逐字相同，宽度变化（旋转 / 分屏）时只重排、不重绑。
 *
 * 分行规则见 [packJustifiedRows]（目标行高见 [JUSTIFIED_ROW_HEIGHT_FACTOR]），结果在数据或宽度变化时
 * 整表重算（O(n)，几千条也是亚毫秒）。
 *
 * 间距刻意做成**与所在行位置无关**：每张卡左右各 space/2，外缘那半格与首行顶部的 space 由本类
 * 覆写的 [getPaddingLeft] / [getPaddingRight] / [getPaddingTop] 让出来。RecyclerView 把装饰间距缓存在
 * 已绑定的 view 上、只在重绑时重算，而翻页补满末行、旋转换宽度都会让已绑定卡片换到行首 / 行尾却不重绑
 * —— 间距若按行位置给，这些卡就会揣着过期间距，行边错位、同行高度差几个像素。
 *
 * 外缘放在 LayoutManager 的 padding 上而不是列表 View 的 padding 上：列表 padding 另有主人
 * （带 toolbar 的页面由 setUpToolbar 的 insets 回调整个覆写成 (0,0,0,导航栏)，详情页按状态栏 /
 * 平板排版设顶部），写在 View 上会被它们随时抹掉。LinearLayoutManager / GridLayoutManager /
 * OrientationHelper 的排版全部经由这几个 getter 取 padding，View 自己的 padding 只管裁剪
 * （这些列表都是 clipToPadding=false）。
 */
internal class JustifiedLayoutManager(
    context: Context,
    /** 手机上「每行几列」设置；目标行高按它折算，平板按宽度自适应加列（同 [ceui.lisa.helper.StaggeredManager.adaptive]）。 */
    private val baseColumns: Int,
    /** 卡片间距，与瀑布流的 SpacesItemDecoration 相同：外缘 space、中缝两侧各 space/2。 */
    val spacePx: Int,
) : GridLayoutManager(context, 1) {

    private var attachedView: RecyclerView? = null

    /** 每张卡左右各占的装饰间距；外缘另一半由下面的 padding getter 让出。 */
    val halfSpacePx: Int = spacePx / 2

    override fun getPaddingLeft(): Int = super.getPaddingLeft() + halfSpacePx

    override fun getPaddingRight(): Int = super.getPaddingRight() + halfSpacePx

    override fun getPaddingTop(): Int = super.getPaddingTop() + spacePx

    private var dirty = true
    private var spans = IntArray(0)
    private var fullSpan = BooleanArray(0)

    init {
        spanSizeLookup = object : SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                ensureRows()
                return spans.getOrNull(position)?.coerceIn(1, spanCount) ?: 1
            }

            // GridLayoutManager 在条目增删改、改跨度数时都会调这里，正好作为分行失效的信号。
            // setSpanCount 只清 index 缓存不清 group 缓存，而重新分行后行号全变了，一并清掉
            override fun invalidateSpanIndexCache() {
                super.invalidateSpanIndexCache()
                invalidateSpanGroupIndexCache()
                dirty = true
            }
        }.apply {
            // 无缓存时每次查 span index 都要从 0 累加一遍，长列表上布局变成 O(n²)
            isSpanIndexCacheEnabled = true
            isSpanGroupIndexCacheEnabled = true
        }
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        attachedView = view
        dirty = true
    }

    override fun onAdapterChanged(
        oldAdapter: RecyclerView.Adapter<*>?,
        newAdapter: RecyclerView.Adapter<*>?,
    ) {
        super.onAdapterChanged(oldAdapter, newAdapter)
        spanSizeLookup.invalidateSpanIndexCache()
        spanSizeLookup.invalidateSpanGroupIndexCache()
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        attachedView = null
    }

    override fun onMeasure(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        widthSpec: Int,
        heightSpec: Int,
    ) {
        // 与 StaggeredManager 同理：在 measure 里改跨度数，随后的 layout 同一帧生效；
        // 0 宽的预测量不算数，否则会在 1 与真实宽度之间来回切
        val view = attachedView
        if (view != null && !view.isComputingLayout &&
            View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED
        ) {
            val contentPx = View.MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
            if (contentPx > 0 && contentPx != spanCount) {
                spanCount = contentPx
            }
        }
        super.onMeasure(recycler, state, widthSpec, heightSpec)
    }

    fun isFullSpanAt(position: Int): Boolean {
        ensureRows()
        return fullSpan.getOrElse(position) { true }
    }

    private fun ensureRows() {
        if (!dirty) return
        val adapter = attachedView?.adapter as? FeedAdapter
        val items = adapter?.currentList.orEmpty()
        val width = spanCount
        val n = items.size
        spans = IntArray(n) { 1 }
        fullSpan = BooleanArray(n)
        dirty = false
        // 还没量到真实宽度 / 还没装 adapter：先全给 1 格；之后的 setSpanCount / 换 adapter 会再失效
        if (adapter == null || width <= 1) return

        // 每张卡的左右装饰间距之和；width 已是扣掉列表 padding 后的内容宽
        val inset = 2 * halfSpacePx
        val columns = columnsFor(width)
        val columnWidth = (width - columns * inset).toFloat() / columns
        val aspects = FloatArray(n) { i ->
            (items[i] as? IllustFeedItem)?.let { 1f / heightRatioOf(it.illust) } ?: 1f
        }
        for (i in 0 until n) fullSpan[i] = adapter.spanSizeAt(i, width) >= width
        spans = packJustifiedRows(
            aspects, fullSpan, width, inset, columnWidth * JUSTIFIED_ROW_HEIGHT_FACTOR,
        )
    }

    private fun columnsFor(widthPx: Int): Int {
        val resources = attachedView?.resources ?: return baseColumns
        return justifiedColumnsFor(resources, widthPx, baseColumns)
    }
}

/**
 * 齐行布局折算目标行高用的列数：手机固定「每行几列」，平板排版按宽度加列（同瀑布流）。
 * 列表与首屏骨架（[IllustLayoutSkeletonView]）共用，两边行高才对得上。
 */
internal fun justifiedColumnsFor(resources: Resources, widthPx: Int, baseColumns: Int): Int =
    if (TabletLayout.isEnabled(resources.configuration)) {
        AdaptiveStaggerColumns.columnsFor(widthPx / resources.displayMetrics.density, baseColumns)
    } else {
        baseColumns
    }.coerceAtLeast(1)

/**
 * [JustifiedLayoutManager] 的间距：每张卡左右各 space/2、底 space，外缘与首行顶部的间距由
 * LayoutManager 的 padding 让出（理由见 [JustifiedLayoutManager]）。
 * [edgeHeaderAtTop] 时首位的整行 header（首页榜单条）用负间距抵掉那圈 padding、贴边贴顶，
 * 与第一行卡片之间留 2 × space，同 SpacesItemWithHeadDecoration。
 * [fullBleedFullSpan] 时所有整行条目都抵掉左右 padding、上下不留白，首位还抵掉顶部那格（作品详情页：
 * 大图 / 简介 / 评论等区块贴边，只有末尾的相关作品卡片有间距，同详情页瀑布流的 RelatedOnlySpaceDecoration）。
 */
internal class JustifiedItemDecoration(
    private val manager: JustifiedLayoutManager,
    private val edgeHeaderAtTop: Boolean = false,
    private val fullBleedFullSpan: Boolean = false,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val half = manager.halfSpacePx
        val space = manager.spacePx
        when {
            !manager.isFullSpanAt(position) -> outRect.set(half, 0, half, space)
            fullBleedFullSpan -> outRect.set(-half, if (position == 0) -space else 0, -half, 0)
            edgeHeaderAtTop && position == 0 -> outRect.set(-half, -space, -half, 2 * space)
            // 其余整行条目（翻页 footer）留在 padding 内，同瀑布流两侧各 space/2
            else -> outRect.set(0, 0, 0, space)
        }
    }
}

/**
 * 带偏移跳到某个位置，[offset] 一律相对**列表 View 的 paddingTop**（即 SGLM 的口径）。
 *
 * 插画列表的 LayoutManager 随「插画列表布局」可能是 SGLM，也可能是齐行的 GridLayoutManager
 * （LinearLayoutManager 子类），两者的 scrollToPositionWithOffset 不在同一个父类上；而且后者的
 * 偏移相对的是 LayoutManager 的 paddingTop —— [JustifiedLayoutManager] 在那上面多让了一格顶部间距，
 * 照搬同一个 offset 会落低一格（跳评论的基线永远对不齐）。这里把差值扣掉。
 */
internal fun RecyclerView.scrollToPositionWithOffset(position: Int, offset: Int) {
    when (val lm = layoutManager) {
        is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(position, offset)
        is LinearLayoutManager ->
            lm.scrollToPositionWithOffset(position, offset - (lm.paddingTop - paddingTop))
        else -> scrollToPosition(position)
    }
}

/**
 * 齐行分行（纯函数，见 JustifiedRowsTest）：返回每个位置占的像素宽，已含每张卡的左右间距 [inset]。
 *
 * - 整行条目（[fullSpan]）占满 [width]，自成一行；
 * - 普通卡片按宽高比 [aspects]（宽 / 高）以 [targetHeight] 往行里排，放得下就继续加；
 *   放下一张会超宽时，比较「收进它（行高缩到 < 目标）」和「停在它之前（行高拉到 > 目标）」，
 *   取行高离目标更近的那个 —— 总是收进去的话，一张宽图就能把整行压得很矮；
 * - 收好的行把行高缩放到正好铺满 [width]，各张宽度按累计值取整；
 * - 末尾凑不满的一行保持目标行高靠左（翻页后会重排）。
 */
internal fun packJustifiedRows(
    aspects: FloatArray,
    fullSpan: BooleanArray,
    width: Int,
    inset: Int,
    targetHeight: Float,
): IntArray {
    val n = aspects.size
    val spans = IntArray(n) { 1 }
    var i = 0
    while (i < n) {
        if (fullSpan[i]) {
            spans[i] = width
            i++
            continue
        }
        val start = i
        var aspectSum = 0f
        var complete = false
        while (i < n && !fullSpan[i]) {
            val aspect = aspects[i]
            val count = i - start + 1
            if ((aspectSum + aspect) * targetHeight + count * inset < width) {
                aspectSum += aspect
                i++
                continue
            }
            val heightWith = ((width - count * inset) / (aspectSum + aspect)).coerceAtLeast(1f)
            val keep = count == 1 ||
                    targetHeight / heightWith <= ((width - (count - 1) * inset) / aspectSum) / targetHeight
            if (keep) {
                aspectSum += aspect
                i++
            }
            complete = true
            break
        }
        val end = i - 1
        val rowHeight =
            if (complete) (width - (end - start + 1) * inset) / aspectSum else targetHeight
        // 按累计宽度取整（而不是每张各自四舍五入、零头全堆给行尾）：每张的宽误差都 ≤ 1px，
        // 同行高度差也就 ≤ 1–2px；否则行尾那张会比同行高出几个像素，GridLayoutManager
        // 按行内最高的那张撑高整行，其余卡片底下露出一道底色
        var exact = 0f
        var edge = 0
        for (k in start..end) {
            exact += aspects[k] * rowHeight + inset
            val next = if (complete && k == end) width else exact.roundToInt().coerceAtMost(width)
            spans[k] = (next - edge).coerceAtLeast(1)
            edge = next
        }
    }
    return spans
}
