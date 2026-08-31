package ceui.pixiv.feeds

import androidx.core.view.isVisible
import ceui.pixiv.feeds.databinding.ItemFeedAppendFooterBinding

/**
 * 框架内部的翻页 footer：追加中转菊花，追加失败变「点击重试」，自动翻页预算用完变
 * 「点击加载更多」。由 [FeedFragment] 根据 [FeedUiState.append] / [FeedUiState.appendPaused]
 * 拼进展示列表，业务不感知。
 */
internal data class AppendFooterItem(
    val state: LoadState,
    /** [FeedUiState.appendPaused]：预算用完等用户点「继续」。与 Loading / Error 互斥。 */
    val paused: Boolean = false,
) : FeedItem {

    override val feedKey: Any
        get() = "feeds:append_footer"
}

internal class AppendFooterRenderer(
    private val onRetry: () -> Unit,
    private val onContinue: () -> Unit,
) : FeedRenderer<AppendFooterItem, ItemFeedAppendFooterBinding>(
    AppendFooterItem::class.java,
    ItemFeedAppendFooterBinding::inflate,
) {

    override val fullSpan: Boolean
        get() = true

    override fun onCreate(cell: FeedCell<AppendFooterItem, ItemFeedAppendFooterBinding>) {
        cell.binding.root.setOnClickListener {
            val item = cell.itemOrNull ?: return@setOnClickListener
            when {
                item.paused -> onContinue()
                item.state is LoadState.Error -> onRetry()
            }
        }
    }

    override fun onBind(cell: FeedCell<AppendFooterItem, ItemFeedAppendFooterBinding>) {
        val isError = cell.item.state is LoadState.Error
        val isPaused = cell.item.paused
        cell.binding.feedFooterProgress.isVisible = !isError && !isPaused
        cell.binding.feedFooterError.isVisible = isError
        cell.binding.feedFooterPaused.isVisible = isPaused
    }
}
