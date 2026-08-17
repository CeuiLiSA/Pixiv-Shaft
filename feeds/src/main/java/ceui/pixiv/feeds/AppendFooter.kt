package ceui.pixiv.feeds

import androidx.core.view.isVisible
import ceui.pixiv.feeds.databinding.ItemFeedAppendFooterBinding

/**
 * 框架内部的翻页 footer：追加中转菊花，追加失败变「点击重试」。
 * 由 [FeedFragment] 根据 [FeedUiState.append] 拼进展示列表，业务不感知。
 */
internal data class AppendFooterItem(val state: LoadState) : FeedItem {

    override val feedKey: Any
        get() = "feeds:append_footer"
}

internal class AppendFooterRenderer(
    private val onRetry: () -> Unit,
) : FeedRenderer<AppendFooterItem, ItemFeedAppendFooterBinding>(
    AppendFooterItem::class.java,
    ItemFeedAppendFooterBinding::inflate,
) {

    override val fullSpan: Boolean
        get() = true

    override fun onCreate(cell: FeedCell<AppendFooterItem, ItemFeedAppendFooterBinding>) {
        cell.binding.root.setOnClickListener {
            if (cell.itemOrNull?.state is LoadState.Error) {
                onRetry()
            }
        }
    }

    override fun onBind(cell: FeedCell<AppendFooterItem, ItemFeedAppendFooterBinding>) {
        val isError = cell.item.state is LoadState.Error
        cell.binding.feedFooterProgress.isVisible = !isError
        cell.binding.feedFooterError.isVisible = isError
    }
}
