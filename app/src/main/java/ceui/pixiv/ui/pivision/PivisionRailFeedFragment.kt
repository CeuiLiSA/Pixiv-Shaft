package ceui.pixiv.ui.pivision

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellPivisionRailBinding
import ceui.lisa.databinding.FragmentPivisionRailFeedBinding
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.lisa.view.HorizontalSpaceDecoration
import ceui.loxia.Article
import ceui.pixiv.feeds.FeedArticleRailSkeletonView
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * 发现页「pixivision 特辑」横向货架（feeds 框架版，替代 legacy FragmentPivisionHorizontal +
 * PivisionHAdapter）。宿主是 [ceui.lisa.fragments.FragmentCenter]（发现 tab）里的
 * `fragment_pivision` 容器，头部是 pixivision logo + 「查看更多」→ 特辑整页。
 *
 * 卡片重做成 MD3-E / V3（[R.layout.cell_pivision_rail]，与竖版 cell_pivision 同一套语言）。
 * 首屏骨架图用 [FeedArticleRailSkeletonView]（一排整卡大小的圆角块）。
 *
 * **与特辑页 illust tab 共用一份本地优先**：两边都是 `category=illust` / slot `pivision-illust`
 * （见 [pivisionSource]），谁先冷启拉到，另一边下次进来秒显。legacy 货架拉的是 `category=all`，
 * 为了共用缓存改成 illust —— 货架不再混入漫画/小说类特辑。
 *
 * **只有一页**：货架不翻页（对齐 legacy PivisionRepo(isHorizontal=true) 的 initNextApi=null），
 * 靠 [SinglePageFeedSource] 把 nextCursor 抹成 null，缓存读写照走。
 */
class PivisionRailFeedFragment : FeedFragment(R.layout.fragment_pivision_rail_feed) {

    private val binding by viewBinding(FragmentPivisionRailFeedBinding::bind)
    private lateinit var palette: V3Palette

    // 与特辑页 illust tab 同 slot(pivision-illust):共用同一份本地优先快照,谁先拉到另一边秒显。
    // 外面套 SinglePageFeedSource:货架不翻页(对齐 legacy),但缓存照读照写。
    override val feedViewModel by feedViewModels {
        SinglePageFeedSource(pivisionSource(PIVISION_CATEGORY_ILLUST))
    }

    // 货架嵌在发现页的 NestedScrollView 里,自己不该再吃竖向下拉(整页刷新归发现页的
    // SmartRefreshLayout);legacy 也只在 onFirstLoaded 开过 refresh、实际点不到。
    override val refreshEnabled: Boolean = false

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.setHasFixedSize(true)
        // 首卡左缘 20dp:和发现页所有货架、标题对齐同一条竖线(fragment_feed 的列表已 clipToPadding=false,
        // 卡照样能滚出去)。卡间距靠只留右间距的 HorizontalSpaceDecoration。
        listView.setPadding(DensityUtil.dp2px(20.0f), 0, DensityUtil.dp2px(8.0f), 0)
        listView.addItemDecoration(HorizontalSpaceDecoration(DensityUtil.dp2px(12.0f)))
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView {
        return FeedArticleRailSkeletonView(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.seeMore.setOnClick {
            startActivity(
                Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra("hideStatusBar", false)
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "特辑")
                },
            )
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        // Renderer 只活到 view 销毁(每次 onViewCreated 重建),可安全捕获 Fragment / palette
        palette = V3Palette.from(requireContext())
        return listOf(railRenderer())
    }

    private fun railRenderer() = feedRenderer<ArticleFeedItem, CellPivisionRailBinding>(
        inflate = CellPivisionRailBinding::inflate,
        create = { cell ->
            val card = cell.binding.cardRoot
            val dp = card.resources.displayMetrics.density
            card.background = palette.settingsCardBg(20f * dp, (1 * dp).toInt())
            card.clipToOutline = true
            cell.binding.category.background = palette.pillPrimary(999f * dp)
            cell.binding.category.setTextColor(palette.floatingPillContent)
            card.setOnClick { openArticle(cell.item.article) }
        },
        recycle = { cell -> cell.binding.cover.clearGlideOnRecycle() },
    ) { cell ->
        val article = cell.item.article
        Glide.with(cell.binding.cover)
            .load(GlideUtil.getUrl(article.thumbnail))
            .placeholder(R.color.light_bg)
            .into(cell.binding.cover)
        cell.binding.title.text = article.title ?: ""
        val label = article.subcategory_label
        cell.binding.category.isVisible = !label.isNullOrEmpty()
        cell.binding.category.text = label ?: ""
    }

    private fun openArticle(article: Article) {
        val url = article.article_url ?: return
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                putExtra(Params.URL, url)
                putExtra(Params.TITLE, getString(R.string.pixiv_special))
                putExtra(Params.PREFER_PRESERVE, true)
            },
        )
    }

}
