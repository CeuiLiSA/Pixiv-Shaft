package ceui.pixiv.ui.trending

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.RecyTagGridBinding
import ceui.loxia.Illust
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.TagItemDecoration
import ceui.loxia.Client
import ceui.loxia.TrendingTag
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl

/**
 * 热门标签页（feeds 框架版，替代 legacy FragmentHotTag + TagAdapter）。
 * 3 列网格，首个标签整行大图（0.66 高宽比），其余方格（1.0）。
 *
 * 与 legacy 的行为对齐点：
 * - 懒加载：宿主 ViewPager 会提前创建本 Fragment，数据等 tab 真正可见（onResume）才拉
 *   （对齐 legacy BaseLazyFragment 的 userVisibleHint 语义，宿主 pager 需用
 *   BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT）；
 * - 点击 → SearchActivity 搜该标签（illust/novel 决定初始 tab）；
 * - 长按 → 该标签代表插画的详情页（单页一次性 PageData，同 legacy TagAdapter）；
 * - 头图 large、格子 medium，间距 TagItemDecoration(3, 1dp)。
 */
class HotTagsFeedFragment : FeedFragment() {

    private val contentType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_CONTENT_TYPE) ?: Params.TYPE_ILLUST
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定（见 feedViewModels 文档）：contentType 取成局部值，不把 Fragment 钉进 VM
        val contentType = contentType
        pixivFeedSource({ Client.appApi.trendingTags(contentType) }) { resp, _ ->
            // 先滤掉无标签名的脏数据：feedKey 取的就是标签名，多条空名会全塌成同一个身份被框架
            // 的 dedupByIdentity 静默丢到只剩一条；何况没有名字的标签本来也点不出搜索结果。
            resp.trend_tags
                .filter { !it.tag.isNullOrEmpty() }
                .mapIndexed { index, trendingTag ->
                    val bean = trendingTag.illust
                    if (index == 0) {
                        HotTagHeaderItem(trendingTag, bean)
                    } else {
                        HotTagGridItem(trendingTag, bean)
                    }
                }
        }
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return gridLayoutManager(SPAN_COUNT)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(
            TagItemDecoration(SPAN_COUNT, DensityUtil.dp2px(1.0f), false)
        )
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            tagRenderer<HotTagHeaderItem>(HEADER_RATIO, fullSpan = true, GlideUtil::getLargeImage),
            tagRenderer<HotTagGridItem>(CONTENT_RATIO, fullSpan = false, GlideUtil::getMediumImg),
        )
    }

    /** header / 格子共用一张 recy_tag_grid，只差跨度、高宽比和缩略图档位。 */
    private inline fun <reified T : HotTagFeedItem> tagRenderer(
        ratio: Float,
        fullSpan: Boolean,
        noinline imageOf: (Illust) -> GlideUrl,
    ) = feedRenderer<T, RecyTagGridBinding>(
        inflate = RecyTagGridBinding::inflate,
        fullSpan = fullSpan,
        create = { cell ->
            cell.binding.root.setOnClick { openSearch(cell.item.trendingTag) }
            cell.binding.root.setOnLongClickListener { openIllustDetail(cell.item.bean) }
        },
        recycle = { cell ->
            cell.binding.illustImage.clearGlideOnRecycle()
        },
    ) { cell ->
        val trendingTag = cell.item.trendingTag
        cell.binding.illustImage.setHeightRatio(ratio)
        Glide.with(cell.binding.illustImage)
            .load(cell.item.bean?.let(imageOf))
            .placeholder(R.color.light_bg)
            .into(cell.binding.illustImage)
        val translated = trendingTag.translated_name
        cell.binding.chineseTitle.text =
            if (translated.isNullOrEmpty()) "" else "#$translated"
        cell.binding.title.text = "#${trendingTag.tag.orEmpty()}"
    }

    private fun openSearch(trendingTag: TrendingTag) {
        startActivity(Intent(requireContext(), SearchActivity::class.java).apply {
            putExtra(Params.KEY_WORD, trendingTag.tag.orEmpty())
            putExtra(Params.INDEX, if (Params.TYPE_ILLUST == contentType) 0 else 1)
        })
    }

    /** 长按 → 代表插画详情（单页一次性 PageData，与主列表互不认领，同 legacy TagAdapter）。 */
    private fun openIllustDetail(bean: Illust?): Boolean {
        if (bean == null) return false
        val pageData = PageData(listOf(bean))
        Container.get().addPageToMap(pageData)
        startActivity(Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, 0)
            putExtra(Params.PAGE_UUID, pageData.getUUID())
        })
        return true
    }

    companion object {
        private const val ARG_CONTENT_TYPE = "hot_tags_content_type"
        private const val SPAN_COUNT = 3
        private const val HEADER_RATIO = 0.66f
        private const val CONTENT_RATIO = 1.0f

        /** [contentType] 取 [Params.TYPE_ILLUST] / [Params.TYPE_NOVEL]。 */
        @JvmStatic
        fun newInstance(contentType: String): HotTagsFeedFragment {
            return HotTagsFeedFragment().apply {
                arguments = Bundle().apply { putString(ARG_CONTENT_TYPE, contentType) }
            }
        }
    }
}

/**
 * 热门标签条目。header 与格子是两种 FeedItem 类型（renderer 按类分发，跨度/比例不同）；
 * 内容相等性只看 immutable 的 [trendingTag]，legacy 可变 [bean] 不参与比较。
 */
sealed class HotTagFeedItem(
    val trendingTag: TrendingTag,
    val bean: Illust?,
) : FeedItem {

    override val feedKey: Any get() = trendingTag.tag.orEmpty()

    override fun equals(other: Any?): Boolean {
        return other is HotTagFeedItem &&
                other.javaClass == javaClass &&
                other.trendingTag == trendingTag
    }

    override fun hashCode(): Int = trendingTag.hashCode()
}

class HotTagHeaderItem(trendingTag: TrendingTag, bean: Illust?) :
    HotTagFeedItem(trendingTag, bean)

class HotTagGridItem(trendingTag: TrendingTag, bean: Illust?) :
    HotTagFeedItem(trendingTag, bean)
