package ceui.pixiv.ui.common

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import kotlinx.coroutines.launch

/** Shares the list's paging and filtering with a viewer that cannot use a Pixiv next URL. */
class IllustDetailFeedPagination(
    private val feed: FeedViewModel<String>,
    private val continueAfterFilteredPages: () -> Unit,
) {
    private val generation = feed.uiState.value.refreshGeneration

    fun bind(owner: LifecycleOwner, works: MutableList<Illust>, onAppended: Runnable) {
        val seen = works.mapTo(HashSet()) { it.id }
        var readCount = 0
        var structureVersion = -1
        owner.lifecycleScope.launch {
            feed.uiState.collect { state ->
                if (state.refreshGeneration != generation) return@collect
                val from = if (state.structureVersion == structureVersion) readCount else 0
                readCount = state.items.size
                structureVersion = state.structureVersion
                val added = state.items.subList(from, readCount).asSequence()
                    .filterIsInstance<IllustFeedItem>()
                    .filterNot { it.hidden }
                    .map { it.illust }
                    .filter { seen.add(it.id) }
                    .toList()
                if (works.addAll(added)) onAppended.run()
            }
        }
    }

    fun loadMore(userGesture: Boolean) {
        val state = feed.uiState.value
        if (state.refreshGeneration != generation || state.refresh is LoadState.Loading) return
        when {
            userGesture && state.append is LoadState.Error -> feed.retryAppend()
            userGesture && state.appendPaused -> feed.continueAppend()
            userGesture && state.reachedEnd -> continueAfterFilteredPages()
            else -> feed.loadMore()
        }
    }
}
