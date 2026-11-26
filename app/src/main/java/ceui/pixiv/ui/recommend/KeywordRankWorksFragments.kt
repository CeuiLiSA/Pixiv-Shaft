package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.shaftapi.KeywordRankWorks
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.common.withMirroredBookmarks
import ceui.pixiv.ui.recommend.KeywordRankFeedFragment.Companion.ARG_ID
import ceui.pixiv.ui.recommend.KeywordRankFeedFragment.Companion.ARG_KEYWORD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.net.HttpURLConnection

internal fun keywordRankWorksFragment(id: String, keyword: String, kind: String): Fragment {
    val fragment = if (kind == RankType.NOVEL) KeywordRankNovelsFragment() else KeywordRankIllustsFragment()
    fragment.arguments = bundleOf(ARG_ID to id, ARG_KEYWORD to keyword)
    return fragment
}

class KeywordRankIllustsFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {
    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)
    override val feedViewModel by feedViewModels {
        keywordRankWorksSource(requireArguments().getString(ARG_ID).orEmpty()) { page ->
            page.illusts.mapNotNull { IllustFeedItem.of(it) }
        }
    }

    override val detailContinuationCursor: String? get() = null
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        super.onCreateRenderers() + feedViewModel.keywordRankContinuationRenderer()

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        feedViewModel.onKeywordRankWorksCommitted()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = requireArguments().getString(ARG_KEYWORD)
    }
}

class KeywordRankNovelsFragment : NovelFeedFragment(R.layout.fragment_toolbar_feed) {
    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)
    override val feedViewModel by feedViewModels {
        keywordRankWorksSource(requireArguments().getString(ARG_ID).orEmpty()) { page ->
            page.novels.mapNotNull { NovelFeedItem.of(it) }
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        super.onCreateRenderers() + feedViewModel.keywordRankContinuationRenderer()

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        feedViewModel.onKeywordRankWorksCommitted()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = requireArguments().getString(ARG_KEYWORD)
    }
}

private fun keywordRankWorksSource(
    id: String,
    map: (KeywordRankWorks) -> List<FeedItem>,
): FeedSource<String> = FeedSource { cursor ->
    val paging = KeywordRankCursor.decode(cursor, keywordRankMaxRestrict())
    val page = Client.pixshaft.keywordRankWorks(
        id = id,
        maxRestrict = paging.restriction,
        offset = paging.position?.toInt(),
        version = paging.version,
        cacheControl = if (cursor == null) "no-cache" else null,
    )
    val items = withContext(Dispatchers.IO) { map(page) }
    FeedPage(items.withMirroredBookmarks(), paging.next(page.next_offset?.toString(), page.page_version))
}

private fun FeedViewModel<String>.onKeywordRankWorksCommitted() {
    val appendError = (uiState.value.append as? LoadState.Error)?.throwable as? HttpException
    if (appendError?.code() == HttpURLConnection.HTTP_CONFLICT) {
        refresh()
        return
    }
    offerKeywordRankContinuation()
}
