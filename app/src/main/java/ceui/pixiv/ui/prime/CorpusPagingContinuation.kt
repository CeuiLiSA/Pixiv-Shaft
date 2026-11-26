package ceui.pixiv.ui.prime

import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.databinding.ItemFeedAppendFooterBinding
import ceui.pixiv.feeds.feedRenderer

private data object CorpusContinueItem : FeedItem {
    override val feedKey: Any get() = "corpus_continue"
}

internal fun FeedUiState.canContinueCorpusPaging(cursor: String?): Boolean =
    reachedEnd && cursor != null && append is LoadState.Idle && refresh !is LoadState.Loading

internal fun FeedViewModel<String>.offerCorpusPagingContinuation() {
    val current = uiState.value
    // Filtering can leave a cursor even after the automatic empty-page hops.
    if (current.canContinueCorpusPaging(currentCursor) && current.items.lastOrNull() !== CorpusContinueItem) {
        appendItems(listOf(CorpusContinueItem))
    }
}

internal fun FeedViewModel<String>.continueCorpusPaging() {
    val cursor = currentCursor ?: return
    if (!uiState.value.canContinueCorpusPaging(cursor)) return
    adoptCursorAndMutateItems(cursor) { items -> items.filterNot { it === CorpusContinueItem } }
    loadMore()
}

internal fun FeedViewModel<String>.corpusPagingContinuationRenderer(): FeedRenderer<out FeedItem, out ViewBinding> =
    feedRenderer<CorpusContinueItem, ItemFeedAppendFooterBinding>(
        inflate = ItemFeedAppendFooterBinding::inflate,
        fullSpan = true,
        create = { cell ->
            cell.binding.feedFooterProgress.isVisible = false
            cell.binding.feedFooterPaused.isVisible = true
            cell.binding.root.setOnClickListener { continueCorpusPaging() }
        },
    ) { }
