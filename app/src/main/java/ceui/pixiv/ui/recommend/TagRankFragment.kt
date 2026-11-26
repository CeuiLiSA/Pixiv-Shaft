package ceui.pixiv.ui.recommend

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellTagRankBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.api.Client
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.shaftapi.CorpusTag
import ceui.pixiv.ui.common.addUserCardDecoration
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.prime.CorpusTagsFragment
import ceui.pixiv.ui.prime.corpusMaxRestrict
import com.bumptech.glide.Glide
import java.text.NumberFormat

class TagRankFragment : FeedFragment(R.layout.fragment_toolbar_feed) {
    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val previewGlide by lazy(LazyThreadSafetyMode.NONE) { Glide.with(this) }
    override val applyBottomSafeInset = true

    override val feedViewModel by feedViewModels<String> {
        FeedSource { cursor ->
            val page = Client.pixshaft.tagRank(r18 = corpusMaxRestrict(), cursor = cursor)
            FeedPage(page.tags.map(::TagRankItem), page.next_cursor)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.tag_rank)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
        listView.addUserCardDecoration()
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        feedRenderer<TagRankItem, CellTagRankBinding>(
            inflate = CellTagRankBinding::inflate,
            create = { cell ->
                cell.binding.setupRankPreviewCard()
                cell.binding.root.setOnClickListener {
                    val intent = Intent(it.context, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CORPUS_TAG_DETAIL.key)
                        putExtra(CorpusTagsFragment.ARG_TAG, cell.item.tag.name)
                    }
                    it.context.startActivity(intent)
                }
            },
            recycle = { cell -> cell.binding.clearRankImagePreviews() },
        ) { cell ->
            val tag = cell.item.tag
            cell.binding.tagName.text = tag.name
            cell.binding.workCount.text = getString(R.string.corpus_tag_work_count, numberFormat.format(tag.count))
            cell.binding.bindRankImagePreviews(tag.preview, previewGlide)
        },
    )

    companion object {
        @JvmStatic
        fun newInstance(): TagRankFragment = TagRankFragment()
    }
}

private data class TagRankItem(val tag: CorpusTag) : FeedItem {
    override val feedKey: Any get() = tag.name
}
