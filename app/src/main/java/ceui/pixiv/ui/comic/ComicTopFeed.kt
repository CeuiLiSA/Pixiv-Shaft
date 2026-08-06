package ceui.pixiv.ui.comic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.CellComicBannerHeaderBinding
import ceui.lisa.databinding.CellComicBannerItemBinding
import ceui.lisa.databinding.CellComicWorkBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.GlideUtil
import ceui.loxia.Client
import ceui.loxia.ComicBanner
import ceui.loxia.ComicWork
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * pixiv COMIC 首页(feeds 框架版)。顶部整行轮播 + 3 列「更新された作品」宫格,对齐官方
 * iOS 端 comic.pixiv.net/api/app/top/v8 那一屏。
 *
 * 该接口没有分页(一次性返回全部 banner 和作品),所以 [FeedPage] 的 nextCursor 恒为 null。
 *
 * 认证复用 pixiv 主 app 的 token,见 [ceui.loxia.ComicApi]。未登录该接口一律 403,
 * 上层照常走 feeds 的错误态。
 */
class ComicTopFeedFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<String> {
        FeedSource { _ ->
            val data = Client.comicApi.getComicTop().data
            // Gson 解析已在 Retrofit 侧完成,这里只做纯映射;仍挪 Default 保住 load 的 main-safe 契约。
            val items = withContext(Dispatchers.Default) {
                buildComicTopItems(data?.banners, data?.recent_updated_official_works)
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.pixiv_comic)
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return gridLayoutManager(SPAN_COUNT)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(bannerHeaderRenderer(), workRenderer())
    }

    /** 顶部轮播(整行)。内层横向列表的 LayoutManager 只在 create 建一次。 */
    private fun bannerHeaderRenderer() =
        feedRenderer<ComicBannerHeaderItem, CellComicBannerHeaderBinding>(
            inflate = CellComicBannerHeaderBinding::inflate,
            fullSpan = true,
            create = { cell ->
                cell.binding.bannerList.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                cell.binding.bannerList.setHasFixedSize(true)
            },
        ) { cell ->
            val item = cell.item
            // 同一批 banner 重复 bind(滚动回收再回来)不重设 adapter,保留横向滚动位置。
            if (cell.binding.bannerList.tag != item) {
                cell.binding.bannerList.tag = item
                cell.binding.bannerList.adapter =
                    ComicBannerAdapter(item.banners) { banner -> openComicUrl(banner.url) }
            }
        }

    private fun workRenderer() = feedRenderer<ComicWorkFeedItem, CellComicWorkBinding>(
        inflate = CellComicWorkBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openWork(cell.item.work) }
        },
        recycle = { cell ->
            cell.binding.cover.clearGlideOnRecycle()
        },
    ) { cell ->
        val work = cell.item.work
        cell.binding.title.text = work.title.orEmpty()
        cell.binding.author.text = work.author.orEmpty()
        cell.binding.storiesCount.text =
            getString(R.string.comic_stories_count, work.stories_count)
        cell.binding.badgeNew.isVisible = work.is_new_work
        Glide.with(cell.binding.cover)
            .load(GlideUtil.getUrl(coverUrlOf(work)))
            .placeholder(R.color.light_bg)
            .into(cell.binding.cover)
    }

    private fun openWork(work: ComicWork) {
        openComicUrl("https://comic.pixiv.net/works/${work.id}")
    }

    /** banner 的 url 是服务端给的站内绝对地址,可能缺失;丢给系统浏览器,失败不崩。 */
    private fun openComicUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        private const val SPAN_COUNT = 3

        /**
         * 响应 → 条目。跑在 Default、被 VM 长期持有,放伴生对象保证零 Fragment 捕获。
         * banner 为空时不插头,免得留一条 150dp 的空白。
         */
        private fun buildComicTopItems(
            banners: List<ComicBanner>?,
            works: List<ComicWork>?,
        ): List<FeedItem> {
            val header = banners
                ?.filter { !it.image_url.isNullOrEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(ComicBannerHeaderItem(it)) }
                .orEmpty()
            return header + works.orEmpty().map { ComicWorkFeedItem(it) }
        }

        /**
         * 封面用 main_image_url,不用 thumbnail_image_url —— 后者实测 18 部作品里 17 部 404
         * (2026-08-06,work_thumbnail 路径整体失效),拿它当主图会得到一屏占位色。
         * thumbnail 只在 main 缺失时兜底。
         */
        private fun coverUrlOf(work: ComicWork): String? {
            return work.main_image_url?.takeIf { it.isNotEmpty() } ?: work.thumbnail_image_url
        }
    }
}

/** 顶部轮播整体作为一条 FeedItem;内容相等性看 banner id 列表,同一批刷新不重绑。 */
class ComicBannerHeaderItem(val banners: List<ComicBanner>) : FeedItem {

    override val feedKey: Any get() = "comic-banner-header"

    override fun equals(other: Any?): Boolean {
        return other is ComicBannerHeaderItem && other.banners.map { it.id } == banners.map { it.id }
    }

    override fun hashCode(): Int = banners.map { it.id }.hashCode()
}

data class ComicWorkFeedItem(val work: ComicWork) : FeedItem {
    override val feedKey: Any get() = work.id
}

/** 轮播内层适配器。banner 数量固定(一次性返回),不做分页也不复用到别处。 */
private class ComicBannerAdapter(
    private val banners: List<ComicBanner>,
    private val onClick: (ComicBanner) -> Unit,
) : RecyclerView.Adapter<ComicBannerAdapter.Holder>() {

    class Holder(val binding: CellComicBannerItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = CellComicBannerItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val holder = Holder(binding)
        binding.root.setOnClick {
            holder.bindingAdapterPosition
                .takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onClick(banners[it]) }
        }
        return holder
    }

    override fun getItemCount(): Int = banners.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val banner = banners[position]
        Glide.with(holder.binding.bannerImage)
            .load(GlideUtil.getUrl(banner.image_url))
            .placeholder(R.color.light_bg)
            .into(holder.binding.bannerImage)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.binding.bannerImage.clearGlideOnRecycle()
    }
}
