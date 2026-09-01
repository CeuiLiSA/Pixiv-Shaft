package ceui.pixiv.ui.notification

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.CellInfoEntryBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick

/**
 * /v1/info/list?cid=N 的下钻页（feeds 框架版）:某分类完整列表,带分页(走 KListShow.next_url，
 * PixivFeedSource 通用翻页机制自动处理)。categoryId/标题通过宿主 Activity 的 intent extra
 * 传入——TemplateActivity 用无参构造创建这个 Fragment,不走 Fragment arguments。
 */
class InfoCategoryListFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    companion object {
        const val EXTRA_CATEGORY_ID = "info_category_id"
        const val EXTRA_CATEGORY_TITLE = "info_category_title"
    }

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val categoryId: Int by lazy {
        requireActivity().intent.getIntExtra(EXTRA_CATEGORY_ID, 0)
    }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获约定:先取成局部 val 再给 PixivFeedSource 用,不捕获 Fragment 本身。
        val cid = categoryId
        pixivFeedSource(initialFetch = { Client.appApi.getInfoList(cid) }) { resp, _ ->
            resp.displayList.map { InfoEntryFeedItem(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        val title = requireActivity().intent.getStringExtra(EXTRA_CATEGORY_TITLE).orEmpty()
        binding.toolbarTitle.text = title.ifEmpty { getString(R.string.tab_info) }
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 ListMode.VERTICAL_NO_MARGIN + 手动挂的 12dp decoration。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(infoEntryRenderer())
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
}
