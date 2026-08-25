package ceui.pixiv.feeds

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding

/**
 * 唯一的 Adapter 实现，页面不需要再写 Adapter 子类。
 *
 * 性能要点：
 * - [ListAdapter]：diff 在后台线程算，主线程只派发最小更新；
 * - [DiffUtil.ItemCallback.getChangePayload] 恒返回非空 → 内容变化复用同一个
 *   ViewHolder 原地重绑，杜绝默认 change 动画对图片列表造成的 crossfade 闪烁；
 * - viewType 就是 renderer 在注册表里的下标，O(1) 双向查找，无 hashCode 碰撞风险；
 * - 触底预取在 onBind 里判断（与 LayoutManager 实现解耦），去重交给
 *   [FeedViewModel.loadMore] 的防重入。
 */
class FeedAdapter(
    renderers: List<FeedRenderer<out FeedItem, out ViewBinding>>,
    private val prefetchDistance: Int = 6,
    private val onNearEnd: (() -> Unit)? = null,
) : ListAdapter<FeedItem, FeedCell<FeedItem, ViewBinding>>(
    FeedDiff(renderers.associateBy { it.itemClass })
) {

    @Suppress("UNCHECKED_CAST")
    private val renderers = renderers as List<FeedRenderer<FeedItem, ViewBinding>>

    private val viewTypeOf: Map<Class<*>, Int> =
        renderers.withIndex().associate { (index, renderer) -> renderer.itemClass to index }

    init {
        require(viewTypeOf.size == renderers.size) {
            "同一种 FeedItem 类型注册了多个 FeedRenderer"
        }
        // 数据住在 VM、submitList 的 diff 又是异步的：视图重建后 RecyclerView 恢复滚动位置的
        // 那次布局 adapter 还空着，pending 位置会被空布局消费丢弃。推迟恢复到首批数据派发之后。
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return viewTypeOf[item.javaClass]
            ?: error("没有为 ${item.javaClass.name} 注册 FeedRenderer")
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): FeedCell<FeedItem, ViewBinding> {
        val renderer = renderers[viewType]
        val binding = renderer.inflate(LayoutInflater.from(parent.context), parent, false)
        val cell = FeedCell<FeedItem, ViewBinding>(binding)
        renderer.onCreate(cell)
        return cell
    }

    // 两参版本是 RecyclerView.Adapter 的抽象方法，必须实现，但框架实际从不走这条路径：
    // bindViewHolder() 内部固定调用三参数版本（payloads 可能是空列表），只有不重写三参版本时
    // 基类默认实现才会转发到这里。三参版本已经覆盖了 payloads 为空的情形，这里只是满足契约。
    override fun onBindViewHolder(cell: FeedCell<FeedItem, ViewBinding>, position: Int) {
        bindInternal(cell, position, emptyList())
    }

    override fun onBindViewHolder(
        cell: FeedCell<FeedItem, ViewBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        bindInternal(cell, position, payloads)
    }

    private fun bindInternal(
        cell: FeedCell<FeedItem, ViewBinding>,
        position: Int,
        payloads: List<Any>,
    ) {
        val renderer = renderers[cell.itemViewType]
        cell.attach(getItem(position))
        if (renderer.fullSpan) {
            (cell.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)
                ?.isFullSpan = true
        }
        if (payloads.isEmpty()) {
            renderer.onBind(cell)
        } else {
            renderer.onBind(cell, payloads)
        }
        if (onNearEnd != null && isNearEnd(position)) {
            onNearEnd.invoke()
        }
    }

    /**
     * [position] 是否已进入触底预取区。onBind 用它点火 [onNearEnd]；
     * [FeedFragment] 在列表提交后也用同一判据补检一次（见 `rearmPaginationIfNearEnd`）。
     */
    fun isNearEnd(position: Int): Boolean = position >= itemCount - prefetchDistance

    override fun onViewRecycled(cell: FeedCell<FeedItem, ViewBinding>) {
        renderers[cell.itemViewType].onRecycled(cell)
    }

    override fun onViewAttachedToWindow(cell: FeedCell<FeedItem, ViewBinding>) {
        super.onViewAttachedToWindow(cell)
        renderers[cell.itemViewType].onAttached(cell)
    }

    override fun onViewDetachedFromWindow(cell: FeedCell<FeedItem, ViewBinding>) {
        renderers[cell.itemViewType].onDetached(cell)
        super.onViewDetachedFromWindow(cell)
    }

    /** 给 GridLayoutManager 的 SpanSizeLookup 用。 */
    fun spanSizeAt(position: Int, spanCount: Int): Int {
        if (position !in 0 until itemCount) return 1
        return renderers[getItemViewType(position)].spanSize(spanCount)
    }
}

/** 兜底 payload：告诉 RecyclerView「复用原 holder 全量重绑」，而不是播放 change 动画。 */
private val FULL_REBIND = Any()

private class FeedDiff(
    private val renderersByClass: Map<Class<*>, FeedRenderer<out FeedItem, out ViewBinding>>,
) : DiffUtil.ItemCallback<FeedItem>() {

    override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
        return oldItem.javaClass == newItem.javaClass && oldItem.feedKey == newItem.feedKey
    }

    // lint 只看得到 FeedItem 这个接口没实现 equals()，看不到实现方是谁。[FeedItem] 的契约
    // 明写了「实现方用 immutable data class，内容比较依赖 data class 的 equals()」——这正是
    // 本框架要求的东西，不是疏漏。真忘了写 equals 的实现会退化成引用比较（内容变化不重绑），
    // 那是实现方违约，靠这条 lint 也拦不住（它对接口一律报警，对具体 data class 一律不报）。
    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: FeedItem, newItem: FeedItem): Any {
        @Suppress("UNCHECKED_CAST")
        val renderer = renderersByClass[oldItem.javaClass] as? FeedRenderer<FeedItem, ViewBinding>
        return renderer?.changePayload(oldItem, newItem) ?: FULL_REBIND
    }
}
