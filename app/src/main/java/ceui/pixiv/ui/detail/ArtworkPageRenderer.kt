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
 * [rebindTick] 仅用于折叠回来时强制首页重绑(内容不变 DiffUtil 不会重绑,而 p0 的
 * 「展开剩余 X 张」覆盖层需要重现)——bump 一下即让 DiffUtil 判为内容变化、原地重绑。
 * feedKey 只认 pageIndex,身份不受 tick 影响。
 */
data class ArtworkPageItem(
    val illustId: Long,
    val pageIndex: Int,
    val rebindTick: Int = 0,
) : FeedItem {
    override val feedKey: Any get() = pageIndex
}

/** 顶部动图(ugoira):内联播放,单条目,无分页。 */
data class ArtworkUgoiraItem(
    val illustId: Long,
) : FeedItem {
    override val feedKey: Any get() = "artwork_ugoira"
}

internal fun ArtworkV3Fragment.artworkPageRenderer() =
    feedRenderer<ArtworkPageItem, RecyIllustDetailBinding>(
        inflate = RecyIllustDetailBinding::inflate,
        fullSpan = true,
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
        adapter.onBindViewHolder(ViewHolder(cell.binding), cell.item.pageIndex)
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
    }
