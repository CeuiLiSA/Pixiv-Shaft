package ceui.pixiv.ui.prime

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellItemPrimeTagBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.api.model.Illust
import ceui.loxia.ImageUrls
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.Utils
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * Prime 标签目录页（feeds 框架版）。目录（`prime_index.json`）仍是内置 assets，几十 KB、
 * 一次性全量读出，没有分页也没有网络请求，因此不接 [FeedSource.loadFromCache] 本地优先
 * 缓存——数据本身就在本地，缓存一层纯属多余。
 *
 * 点进某个标签之后的插画才走网络（见 [PrimeTagDetailFragment]）。
 */
class PrimeTagsFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<String> {
        FeedSource { _ ->
            val items = withContext(Dispatchers.IO) {
                val json = Utils.getApp().assets.open(INDEX_FILE).bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<PrimeTagIndexItem>>() {}.type
                val indexItems: List<PrimeTagIndexItem> = Shaft.sGson.fromJson(json, type)
                indexItems.map { PrimeTagItemHolder(it) }
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.prime_tags)
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 ListMode.VERTICAL 的间距：卡片左右/底部 18dp 间隔 + 首项顶部留白
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(primeTagRenderer())
    }

    private fun primeTagRenderer() = feedRenderer<PrimeTagItemHolder, CellItemPrimeTagBinding>(
        inflate = CellItemPrimeTagBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener { onClickPrimeTag(cell.item.indexItem) }
        },
    ) { cell ->
        cell.binding.holder = cell.item
    }

    private fun onClickPrimeTag(indexItem: PrimeTagIndexItem) {
        // file_path 不合规就没有服务端 key，这条目录项点不开——静默忽略，别开一个必然空的页。
        val key = indexItem.tagKey ?: return
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PRIME_TAG_DETAIL.key)
        intent.putExtra("name", indexItem.tag.translated_name)
        intent.putExtra("key", key)
        startActivity(intent)
    }

    companion object {
        private const val INDEX_FILE = "pixiv_prime/prime_index.json"
    }
}

data class PrimeTagItemHolder(val indexItem: PrimeTagIndexItem) : FeedItem {

    override val feedKey: Any get() = indexItem.filePath

    val primeTag: PrimeTagIndexItem get() = indexItem

    val illust0: Illust?
        get() = indexItem.previewSquareUrls.getOrNull(0)?.toPreviewIllust()
    val illust1: Illust?
        get() = indexItem.previewSquareUrls.getOrNull(1)?.toPreviewIllust()
    val illust2: Illust?
        get() = indexItem.previewSquareUrls.getOrNull(2)?.toPreviewIllust()
}

private fun String.toPreviewIllust(): Illust {
    return Illust(id = 0, image_urls = ImageUrls(square_medium = this))
}
