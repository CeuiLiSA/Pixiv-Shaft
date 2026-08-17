package ceui.pixiv.feeds

import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 通用 ViewHolder：只负责持有 binding 和当前条目，行为全部在 [FeedRenderer] 里。
 *
 * [item] 让 onCreate 阶段注册的点击监听可以拿到「点击发生那一刻」绑定的条目——
 * 监听器只创建一次，绑定零 lambda 分配。
 */
class FeedCell<T : FeedItem, VB : ViewBinding> internal constructor(
    val binding: VB,
) : RecyclerView.ViewHolder(binding.root) {

    private var _item: T? = null

    /** 当前绑定的条目；在首次 onBind 之前访问会抛错（onCreate 里请用 [itemOrNull]）。 */
    val item: T
        get() = checkNotNull(_item) { "FeedCell 还没有绑定过数据，onCreate 阶段请改用 itemOrNull" }

    val itemOrNull: T?
        get() = _item

    @Suppress("UNCHECKED_CAST")
    internal fun attach(item: FeedItem) {
        _item = item as T
    }
}
