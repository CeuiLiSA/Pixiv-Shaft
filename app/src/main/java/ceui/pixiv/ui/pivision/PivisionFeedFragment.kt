package ceui.pixiv.ui.pivision

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellPivisionBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Article
import ceui.pixiv.feeds.FeedArticleSkeletonView
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * 「特辑」(pixivision) 的 illust / manga tab（feeds 框架版，替代 legacy FragmentPivision +
 * ArticleAdapter + PivisionRepo）。宿主是 [ceui.lisa.fragments.FragmentPv] 的 ViewPager。
 *
 * 卡片重做成 MD3-E / V3（[R.layout.cell_pivision]，与约稿卡 cell_request_plan 同一套语言）：
 * 封面大图 + 渐变遮罩 + 标题压图上 + 分类 tonal 胶囊 + 日期/CTA 内容区。点击开 pixivision 网页
 * （TemplateActivity「网页链接」，preferPreserve=true，对齐 legacy）。
 * 首屏骨架图用 [FeedArticleSkeletonView]（大图 + 标题条），不是瀑布流那种等宽块。
 */
class PivisionFeedFragment : FeedFragment() {

    private lateinit var palette: V3Palette

    private val dataType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.DATA_TYPE) ?: TYPE_ILLUST
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定:category 先取成局部值,不把 Fragment 钉进长命 VM。
        // illust 页与发现页货架同 slot(pivision-illust) → 共用同一份本地优先快照。
        val category = dataType
        pivisionSource(category)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.setHasFixedSize(true)
        // 卡间距/左右边距统一由 decoration 给,cell 本身不加 margin(同 recy_novel 的约定)
        listView.addItemDecoration(LinearItemDecoration(16.ppppx))
    }

    /** 竖向特辑卡的骨架图（大图 + 标题条）；基类默认只给瀑布流出骨架，这里是 Linear。 */
    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView {
        return FeedArticleSkeletonView(requireContext())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        // Renderer 只活到 view 销毁(每次 onViewCreated 重建),可安全捕获 Fragment / palette
        palette = V3Palette.from(requireContext())
        return listOf(articleRenderer())
    }

    private fun articleRenderer() = feedRenderer<ArticleFeedItem, CellPivisionBinding>(
        inflate = CellPivisionBinding::inflate,
        create = { cell ->
            val card = cell.binding.cardRoot
            val dp = card.resources.displayMetrics.density
            // 卡底 = V3 settings-card 同款(圆角填充 + 12% 主题色 hairline);clipToOutline 让封面
            // 大图的上圆角跟着裁。用普通 View 而非 MaterialCardView 以兼容 QMUI/AppCompat 宿主主题。
            card.background = palette.settingsCardBg(28f * dp, (1 * dp).toInt())
            card.clipToOutline = true
            cell.binding.category.background = palette.pillPrimary(999f * dp)
            cell.binding.category.setTextColor(palette.floatingPillContent)
            cell.binding.publishDate.setTextColor(palette.textSecondary)
            cell.binding.cta.setTextColor(palette.textAccent)
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
        cell.binding.publishDate.text = article.publish_date?.take(10) ?: ""
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

    companion object {
        const val TYPE_ILLUST = PIVISION_CATEGORY_ILLUST
        const val TYPE_MANGA = PIVISION_CATEGORY_MANGA

        @JvmStatic
        fun newInstance(dataType: String): PivisionFeedFragment {
            return PivisionFeedFragment().apply {
                arguments = Bundle().apply { putString(Params.DATA_TYPE, dataType) }
            }
        }
    }
}
