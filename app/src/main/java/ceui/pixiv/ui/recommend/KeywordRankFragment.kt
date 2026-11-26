package ceui.pixiv.ui.recommend

import android.content.Intent
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellKeywordRankBinding
import ceui.lisa.databinding.ItemKeywordRankNovelPreviewBinding
import ceui.pixiv.api.Client
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.databinding.ItemFeedAppendFooterBinding
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.shaftapi.KeywordRankEntry
import ceui.pixiv.shaftapi.KeywordRankNovelPreview
import ceui.pixiv.ui.common.addUserCardDecoration
import ceui.pixiv.ui.navigation.TemplateRoute
import com.bumptech.glide.Glide
import java.text.NumberFormat

class KeywordRankFragment : TypeTabsRankFragment() {
    override val titleRes = R.string.keyword_rank
    override val types = listOf(RankType.ILLUST, RankType.NOVEL)

    override fun tabTitleRes(type: String): Int =
        if (type == RankType.NOVEL) R.string.type_novel else R.string.dynamic_type_illust_manga

    override fun createPage(type: String): Fragment = KeywordRankFeedFragment().apply {
        arguments = bundleOf(KeywordRankFeedFragment.ARG_KIND to type)
    }
}

class KeywordRankFeedFragment : FeedFragment() {
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val previewGlide by lazy(LazyThreadSafetyMode.NONE) { Glide.with(this) }
    override val applyBottomSafeInset = true

    override val feedViewModel by feedViewModels<String>(autoLoad = false) {
        val kind = requireArguments().getString(ARG_KIND) ?: RankType.ILLUST
        FeedSource { cursor ->
            val paging = KeywordRankCursor.decode(cursor, keywordRankMaxRestrict())
            val page = Client.pixshaft.keywordRank(kind, paging.restriction, paging.position)
            FeedPage(page.items.map(::KeywordRankItem), paging.next(page.next_cursor))
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
        listView.addUserCardDecoration()
    }

    override fun onListCommitted(state: FeedUiState) {
        feedViewModel.offerKeywordRankContinuation()
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        feedRenderer<KeywordRankItem, CellKeywordRankBinding>(
            inflate = CellKeywordRankBinding::inflate,
            create = { cell ->
                cell.binding.setupRankPreviewCard()
                cell.binding.root.setOnClickListener {
                    val entry = cell.item.entry
                    val context = it.context
                    context.startActivity(Intent(context, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.KEYWORD_RANK_WORKS.key)
                        putExtra(ARG_ID, entry.id)
                        putExtra(ARG_KEYWORD, entry.keyword)
                        putExtra(ARG_KIND, entry.kind)
                    })
                }
            },
            recycle = { cell -> cell.binding.clearRankImagePreviews() },
        ) { cell ->
            val entry = cell.item.entry
            val novel = entry.kind == RankType.NOVEL
            val type = if (novel) R.string.type_novel else R.string.dynamic_type_illust_manga
            val binding = cell.binding
            binding.keyword.text = entry.keyword
            binding.workCount.text = getString(
                R.string.keyword_rank_work_count, getString(type), numberFormat.format(entry.workCount),
            )
            binding.heat.text = getString(R.string.keyword_rank_heat, numberFormat.format(entry.heat))
            binding.bindRankImagePreviews(if (novel) emptyList() else entry.preview.orEmpty(), previewGlide)
            val excerpts = if (novel) entry.novelPreview.orEmpty() else emptyList()
            binding.novelPreviews.isVisible = excerpts.isNotEmpty()
            binding.novelOne.bindExcerpt(excerpts.getOrNull(0))
            binding.novelTwo.bindExcerpt(excerpts.getOrNull(1))
            binding.novelThree.bindExcerpt(excerpts.getOrNull(2))
        },
        feedViewModel.keywordRankContinuationRenderer(),
    )

    private fun ItemKeywordRankNovelPreviewBinding.bindExcerpt(preview: KeywordRankNovelPreview?) {
        root.isVisible = preview != null
        if (preview == null) return
        novelTitle.text = preview.title
        novelExcerpt.text = preview.excerpt
        novelExcerpt.isVisible = !preview.excerpt.isNullOrBlank()
    }

    companion object {
        const val ARG_KIND = "keywordRankKind"
        const val ARG_ID = "keywordRankId"
        const val ARG_KEYWORD = "keywordRankWord"
    }
}

private data class KeywordRankItem(val entry: KeywordRankEntry) : FeedItem {
    override val feedKey: Any get() = entry.id
}

private data object KeywordRankContinueItem : FeedItem {
    override val feedKey: Any get() = "keyword_rank_continue"
}

internal fun FeedUiState.canContinueKeywordRank(cursor: String?): Boolean =
    reachedEnd && cursor != null && append is LoadState.Idle && refresh !is LoadState.Loading

internal fun FeedViewModel<String>.offerKeywordRankContinuation() {
    val current = uiState.value
    // Filtering can leave a cursor even after the automatic empty-page hops.
    if (current.canContinueKeywordRank(currentCursor) && current.items.lastOrNull() !== KeywordRankContinueItem) {
        appendItems(listOf(KeywordRankContinueItem))
    }
}

internal fun FeedViewModel<String>.keywordRankContinuationRenderer(): FeedRenderer<out FeedItem, out ViewBinding> =
    feedRenderer<KeywordRankContinueItem, ItemFeedAppendFooterBinding>(
        inflate = ItemFeedAppendFooterBinding::inflate,
        fullSpan = true,
        create = { cell ->
            cell.binding.feedFooterProgress.isVisible = false
            cell.binding.feedFooterPaused.isVisible = true
            cell.binding.root.setOnClickListener {
                val cursor = currentCursor ?: return@setOnClickListener
                if (!uiState.value.canContinueKeywordRank(cursor)) return@setOnClickListener
                adoptCursorAndMutateItems(cursor) { items -> items.filterNot { it === KeywordRankContinueItem } }
                loadMore()
            }
        },
    ) { }

internal fun keywordRankMaxRestrict(): Int = if (Shaft.sSettings.isR18FilterTempEnable) 0 else 2

internal data class KeywordRankCursor(val restriction: Int, val position: String?, val version: String? = null) {
    fun next(position: String?, version: String? = null): String? = position?.let {
        if (version == null) "$restriction:$it" else "$restriction:$it:$version"
    }

    companion object {
        // A failed refresh leaves the previously committed next cursor in FeedViewModel.
        // Keep the age filter with that cursor so the old page can still be continued.
        fun decode(cursor: String?, initialRestriction: Int): KeywordRankCursor = if (cursor == null) {
            KeywordRankCursor(initialRestriction, null)
        } else {
            val parts = cursor.split(':', limit = 3)
            KeywordRankCursor(parts[0].toInt(), parts[1], parts.getOrNull(2))
        }
    }
}
