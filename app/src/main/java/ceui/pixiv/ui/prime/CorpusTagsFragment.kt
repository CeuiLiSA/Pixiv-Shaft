package ceui.pixiv.ui.prime

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellItemCorpusTagBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.ImageUrls
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.shaftapi.CorpusTag
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.Utils
import java.text.NumberFormat

/**
 * 「热门搜索」的标签目录 —— 一级页。
 *
 * 和 [PrimeTagsFragment] 是同一种货架，数据来路完全不同：那份目录是一年前策展、随 APK
 * 发布的 202 个标签，这份来自 pixshaft-api 的作品库，**每天都在自己变厚**。搜索缓存每回填
 * 一页，服务端就把那 30 个作品拆出来按 id 去重存下来，标签目录跟着重算——所以谁搜出来的
 * 都算数，看的人不用登录、不占额度，服务端也从不替我们去请求 pixiv。
 *
 * 目录只有一页（服务端按作品数取前 [CATALOG_LIMIT] 个），所以游标恒为 null：这不是一条
 * 无尽流，是一份货架清单。点进某个标签之后的作品才翻页（见 [CorpusTagDetailFragment]）。
 */
class CorpusTagsFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<String> {
        FeedSource { _ ->
            val list = Client.pixshaft.corpusTags(limit = CATALOG_LIMIT, minWorks = SHELF_MIN_WORKS)
            FeedPage(list.tags.map { CorpusTagItemHolder(it) }, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.corpus_library)
    }

    override fun onListReady(listView: RecyclerView) {
        // 和 PrimeTagsFragment 同一套间距，两份目录看起来是同一种东西。
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(corpusTagRenderer())
    }

    private fun corpusTagRenderer() = feedRenderer<CorpusTagItemHolder, CellItemCorpusTagBinding>(
        inflate = CellItemCorpusTagBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener { openTag(cell.item.tag) }
        },
    ) { cell ->
        cell.binding.holder = cell.item
    }

    private fun openTag(tag: CorpusTag) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CORPUS_TAG_DETAIL.key)
        intent.putExtra(ARG_TAG, tag.name)
        startActivity(intent)
    }

    companion object {
        const val ARG_TAG = "corpusTag"

        /** 服务端把 limit 卡在 200；目录是一屏屏翻的清单，不是无尽流。 */
        private const val CATALOG_LIMIT = 200

        /** 少于这个数的标签点进去凑不满一屏，不值得摆进货架。 */
        private const val SHELF_MIN_WORKS = 30
    }
}

/**
 * 目录里的一条。预览图借 [Illust] 这个壳传给 `loadSquareMedia`（同 [PrimeTagItemHolder]），
 * 它们只是缩略图，不是可点开的作品。
 */
data class CorpusTagItemHolder(val tag: CorpusTag) : FeedItem {

    override val feedKey: Any get() = tag.name

    val countText: String
        get() = Utils.getApp().getString(
            R.string.corpus_tag_work_count,
            NumberFormat.getIntegerInstance().format(tag.count),
        )

    val illust0: Illust? get() = tag.preview.getOrNull(0)?.toPreviewIllust()
    val illust1: Illust? get() = tag.preview.getOrNull(1)?.toPreviewIllust()
    val illust2: Illust? get() = tag.preview.getOrNull(2)?.toPreviewIllust()
}

private fun String.toPreviewIllust(): Illust {
    return Illust(id = 0, image_urls = ImageUrls(square_medium = this))
}
