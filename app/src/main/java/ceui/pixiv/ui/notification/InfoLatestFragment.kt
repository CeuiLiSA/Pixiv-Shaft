package ceui.pixiv.ui.notification

import android.content.Context
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellInfoCategoryHeaderBinding
import ceui.lisa.databinding.CellInfoEntryBinding
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.CategorizedInfo
import ceui.loxia.Client
import ceui.loxia.InfoItem
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * "公告" tab 首屏（feeds 框架版）:/v1/info/latest 聚合视图,header + 4-5 个 entry / 分类。
 * 点 entry → 走 WebView 看公告页。点 "查看更多" → 下钻该分类的分页列表。
 * 父 NotificationPagerFragment 自带 toolbar + tab bar,本页用裸 fragment_feed（默认
 * contentLayoutId），不需要自己的 toolbar。
 */
class InfoLatestFragment : FeedFragment() {

    override val feedViewModel by feedViewModels<String> {
        pixivFeedSource(initialFetch = { Client.appApi.getInfoLatest() }) { resp, _ ->
            resp.displayList.flatMap { category ->
                listOf(InfoCategoryHeaderFeedItem(category, showMore = true)) +
                    category.info_list.map { InfoEntryFeedItem(it) }
            }
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(infoCategoryHeaderRenderer(), infoEntryRenderer())
    }

    private fun infoCategoryHeaderRenderer() =
        feedRenderer<InfoCategoryHeaderFeedItem, CellInfoCategoryHeaderBinding>(
            inflate = CellInfoCategoryHeaderBinding::inflate,
            create = { cell ->
                cell.binding.categoryMore.setOnClick {
                    val item = cell.itemOrNull ?: return@setOnClick
                    if (item.showMore) onClickInfoCategoryMore(item.category)
                }
            },
        ) { cell ->
            cell.binding.bindInfoCategoryHeader(cell.item)
        }

    private fun infoEntryRenderer() = feedRenderer<InfoEntryFeedItem, CellInfoEntryBinding>(
        inflate = CellInfoEntryBinding::inflate,
        create = { cell ->
            cell.binding.infoEntryRoot.setOnClick {
                val item = cell.itemOrNull?.item ?: return@setOnClick
                openInfoUrl(requireContext(), item)
            }
        },
    ) { cell ->
        cell.binding.bindInfoEntry(cell.item.item)
    }

    private fun onClickInfoCategoryMore(category: CategorizedInfo) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.INFO_CATEGORY.key)
            putExtra(InfoCategoryListFragment.EXTRA_CATEGORY_ID, category.category_id)
            putExtra(InfoCategoryListFragment.EXTRA_CATEGORY_TITLE, category.category_title.orEmpty())
        }
        startActivity(intent)
    }
}

/** info entry 跳 WebView (走 TemplateActivity 现有"网页链接"路由)。 */
internal fun openInfoUrl(ctx: Context, item: InfoItem) {
    val url = item.url ?: return
    val intent = Intent(ctx, TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key)
        putExtra(Params.URL, url)
        putExtra(Params.TITLE, item.title.orEmpty())
    }
    ctx.startActivity(intent)
}
