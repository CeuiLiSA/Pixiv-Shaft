package ceui.pixiv.ui.detail

import ceui.lisa.R
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.ItemArtworkUgoiraBinding
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedRenderer

/**
 * 顶部大图 —— 逐页原生 [FeedItem],外层瀑布流回收每页。
 *
 * **不重新实现取图 / 尺寸 / 折叠逻辑**:每页的 bind/recycle 直接委托给 Fragment 持有的那一个
 * [ceui.lisa.adapters.IllustAdapter] / [CollapsibleIllustAdapter] 实例——把本页 binding 包成
 * 一个 [ViewHolder] 交给 `adapter.onBindViewHolder(vh, pageIndex)`。这样单 P 矮 / 单 P 高 /
 * 多 P 矮 / 多 P 高的尺寸规则、FIT_CENTER 居中、最小高度兜底、floorCoverHeight、large→原图
 * overlay、进度环 / 重试 / stale-tag / #912 observer detach、折叠「展开剩余 X 张」覆盖层——
 * 全部沿用 legacy 那份 battle-tested 代码,行为逐字一致。
 *
 * 折叠 / 展开靠 adapter 的 [CollapsibleIllustAdapter.onExpandedChanged] 回调驱动 feed 列表
 * (插 / 删剩余页条目),见 [ArtworkV3Fragment.onPagesExpandedChanged]。
 */

/**
 * 顶部静态图的一页。折叠时列表只含 pageIndex=0;展开后含 0..N-1。
 *
 * [rebindTick] 驱动图片重绑（加载原图 / 本地下载刷新）；[overlayTick] 仅刷新折叠覆盖层。
 * 两种更新必须分开，否则折叠态下的图片刷新会被覆盖层 payload 吞掉。
 */
open class ArtworkPageItem(
    val illustId: Long,
    val pageIndex: Int,
    val rebindTick: Int = 0,
    val overlayTick: Int = 0,
) : FeedItem {
    override val feedKey: Any get() = pageIndex

    /** 换 tick 但**保住 javaClass**:viewType 就是类,换类等于换回收池的桶。 */
    open fun withRebindTick(tick: Int): ArtworkPageItem =
        ArtworkPageItem(illustId, pageIndex, tick, overlayTick)

    open fun withOverlayTick(tick: Int): ArtworkPageItem =
        ArtworkPageItem(illustId, pageIndex, rebindTick, tick)

    // 不再是 data class 是**故意的**:常驻页要用 [ArtworkPinnedFirstPageItem] 单独占一个 viewType,
    // 而 feeds 的 viewType 就是条目的 javaClass —— 想要子类就不能是 data class。equals 因此手写,
    // 语义与原来的 data class 一致,只多一条「类型必须相同」。
    override fun equals(other: Any?): Boolean =
        other is ArtworkPageItem && other.javaClass == javaClass &&
            other.illustId == illustId && other.pageIndex == pageIndex &&
            other.rebindTick == rebindTick && other.overlayTick == overlayTick

    override fun hashCode(): Int =
        (((javaClass.hashCode() * 31 + illustId.hashCode()) * 31 + pageIndex) * 31 + rebindTick) * 31 + overlayTick
}

/**
 * p0 的条目 —— 收起回顶的落点,唯一被常驻的一页。与 [ArtworkPageItem] 分成两个类是刻意的。
 *
 * feeds 的 viewType 就是条目的 javaClass,而 RecyclerView 的回收池**按 viewType 分桶、取出是
 * LIFO** —— 只有独立成类,这一格的 holder 才会独占一个桶,不跟普通页共用那几格 `mCachedViews`
 * 窗口,也不会被后来的页顶掉。槽位因此能长住,而它留着的图仍由 Glide 的引用计数持有
 * (见 `IllustAdapter` 的 `onViewRecycled` 对常驻页不清图),收起回顶才能一帧到位。
 *
 * 只保 p0:两枚胶囊(「展开剩余 X 张」「用阅读器看」)硬判 `position == 0`,且贴在 p0 那条 item
 * 自己布局的下沿 —— 收起后的落点恒为 p0,p1 没有任何需要常驻的地方,它按普通页走(重载有淡入)。
 *
 * ⚠️ 真要保 p1 的话**必须再给它一个独立的类**,不能塞进这个类:同一个桶里躺两格时,取出来的
 * 永远是最后进去的那格 —— p1 的 holder 会被拿去当 p0 用,`tryKeepPinnedPage` 判成错配、只能
 * 清掉重下。分桶之后每桶只有一格,原主回来必然对上。
 */
class ArtworkPinnedFirstPageItem(
    illustId: Long,
    pageIndex: Int,
    rebindTick: Int = 0,
    overlayTick: Int = 0,
) : ArtworkPageItem(illustId, pageIndex, rebindTick, overlayTick) {
    override fun withRebindTick(tick: Int) = ArtworkPinnedFirstPageItem(illustId, pageIndex, tick, overlayTick)
    override fun withOverlayTick(tick: Int) = ArtworkPinnedFirstPageItem(illustId, pageIndex, rebindTick, tick)
}

/**
 * 按页号产出**正确的那一类**条目。别直接 `ArtworkPageItem(...)` —— 常驻页要是拿到了普通类,
 * 它的 holder 就进了普通桶,常驻槽位整条失效。
 */
internal fun artworkPageItem(illustId: Long, pageIndex: Int, rebindTick: Int = 0): ArtworkPageItem =
    if (pageIndex < IllustAdapter.PINNED_PAGE_COUNT) {
        ArtworkPinnedFirstPageItem(illustId, pageIndex, rebindTick)
    } else {
        ArtworkPageItem(illustId, pageIndex, rebindTick)
    }

/** 顶部动图(ugoira):内联播放,单条目,无分页。 */
data class ArtworkUgoiraItem(
    val illustId: Long,
) : FeedItem {
    override val feedKey: Any get() = "artwork_ugoira"
}

internal fun ArtworkV3Fragment.artworkPageRenderer() = pageRenderer<ArtworkPageItem>()

/**
 * 常驻页(p0)的 renderer:绑定逻辑与 [artworkPageRenderer] 逐字相同,只是条目类不同 —— 于是
 * 它拿到自己的 viewType、独占回收池里一个桶,见 [ArtworkPinnedFirstPageItem]。
 */
internal fun ArtworkV3Fragment.artworkPinnedFirstPageRenderer() =
    pageRenderer<ArtworkPinnedFirstPageItem>()

/** 两个页 renderer 共用的一份绑定逻辑;两个入口只为拿不同的 viewType。 */
private inline fun <reified T : ArtworkPageItem> ArtworkV3Fragment.pageRenderer() =
    feedRenderer<T, RecyIllustDetailBinding>(
        inflate = RecyIllustDetailBinding::inflate,
        fullSpan = true,
        // 只有纯折叠变化可以跳过取图；原图切换、本地下载刷新仍走全量绑定。
        changePayload = { oldItem, newItem ->
            if (oldItem.pageIndex == newItem.pageIndex && oldItem.illustId == newItem.illustId &&
                oldItem.rebindTick == newItem.rebindTick && oldItem.overlayTick != newItem.overlayTick) {
                PAYLOAD_EXPAND_OVERLAY_ONLY
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            val delegate =
                cell.itemView.getTag(R.id.tag_artwork_page_adapter) as? CollapsibleIllustAdapter
            payloads.isNotEmpty() && payloads.all { it === PAYLOAD_EXPAND_OVERLAY_ONLY } &&
                delegate === ensurePageAdapter() &&
                delegate?.bindOverlayOnly(ViewHolder(cell.binding), cell.item.pageIndex) == true
        },
        recycle = { cell ->
            // 委托给同一个 adapter 清理(detach observer + clear Glide)。ViewHolder 只是薄壳,
            // 读的是 itemView tag,新建一个包住同一 binding 即可。delegate 存在 cell tag 上，
            // 即使 Fragment.onDestroyView 已释放/清空 pageAdapter，晚到的回收仍能完整清理。
            val delegate = cell.itemView.getTag(R.id.tag_artwork_page_adapter) as? IllustAdapter
            delegate?.onViewRecycled(ViewHolder(cell.binding))
            cell.itemView.setTag(R.id.tag_artwork_page_adapter, null)
        },
    ) { cell ->
        val adapter = ensurePageAdapter() ?: return@feedRenderer
        cell.itemView.setTag(R.id.tag_artwork_page_adapter, adapter)
        val holder = ViewHolder(cell.binding)
        // 常驻槽位命中:这一格留着的正是这一页的图(见 [ArtworkPinnedFirstPageItem] 与
        // IllustAdapter 的 onViewRecycled),取图整条都不用走 —— 没有请求,也就没有环和底色。
        if (adapter.tryKeepPinnedPage(holder, cell.item.pageIndex)) {
            // 但**覆盖层不能跟着跳过**:它跟着折叠 / 展开变,折叠回来时那层 scrim 和
            // 「展开剩余 X 张」「用阅读器看」两枚胶囊要重新出现。也不能指望 overlay-only
            // payload —— 回收时 itemView 上的 adapter tag 被置空,bindPayloads 取不到 delegate
            // 会返回 false,正是回退到这条路径来的。
            (adapter as? CollapsibleIllustAdapter)
                ?.refreshExpandOverlay(holder, cell.item.pageIndex)
            return@feedRenderer
        }
        adapter.onBindViewHolder(holder, cell.item.pageIndex)
        if (isSnapshotMode) {
            // 点大图不在这里另起一份 intent:上一行的 adapter.onBindViewHolder 已经按
            // AbstractIllustAdapter.snapshotId 路由到「快照大图」了,而且它带了
            // EXTRA_ENTER_BOUNDS —— 大图页(透明窗口)靠这个矩形从点击处展开进场。
            // 在这里覆盖掉等于把过渡动画退化成居中淡入,还让同一条路由散成两份会漂移的副本。
            // 长按仍要显式置空:IllustAdapter 在快照模式下不再挂长按下载,但复用的 itemView
            // 上可能还留着上一次绑定的监听。
            cell.itemView.setOnLongClickListener(null)
        }
    }

internal fun ArtworkV3Fragment.artworkUgoiraRenderer() =
    feedRenderer<ArtworkUgoiraItem, ItemArtworkUgoiraBinding>(
        inflate = ItemArtworkUgoiraBinding::inflate,
        fullSpan = true,
        attach = { cell -> cell.binding.root.onFeedAttached() },
        detach = { cell -> cell.binding.root.onFeedDetached() },
        recycle = { cell -> cell.binding.root.recycle() },
    ) { cell ->
        val illust: Illust = ObjectPool.get<Illust>(cell.item.illustId).value
            ?: return@feedRenderer
        val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        cell.binding.root.bind(viewLifecycleOwner, illust, maxHeight)
        cell.binding.root.setOnClickListener { openUgoiraViewer(it, illust) }
    }

/**
 * 折叠回来时 p0 只需要刷新「展开剩余 X 张」覆盖层，不该重走取图。
 * 消费方是 [artworkPageRenderer] 的 bindPayloads，兜底由框架负责（返回 false 即全量重绑）。
 */
private val PAYLOAD_EXPAND_OVERLAY_ONLY = Any()
