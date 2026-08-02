package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.databinding.RecyNovelMarkersBinding
import ceui.lisa.model.ListNovelMarkers
import ceui.lisa.models.MarkedNovelItem
import ceui.lisa.models.TagsBean
import ceui.lisa.repo.NovelMarkersRepo
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.tryOpenNovelReaderDirect
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 「小说书签」列表页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentNovelMarkers] +
 * NovelMarkersAdapter）。宿主是 TemplateActivity，用 feeds 独立页统一的 fragment_toolbar_feed
 * （webview 5 件套 toolbar），toolbar 标题 [R.string.core_string_novel_marker]。
 *
 * 卡形与交互 1:1 复刻 legacy NovelMarkersAdapter 的 recy_novel_markers（databinding 生成的
 * [RecyNovelMarkersBinding] 天然实现 ViewBinding，直接喂 [feedRenderer]，对齐 MutedUserFeedFragment
 * 复用 recy_simple_user 的做法）：封面 / 头像 / 标题 / 系列 / 日期 / 标签 / 字数 / 收藏数 / 书签按钮，
 * 以及全部子点击（整卡进小说详情、封面看大图、头像作者进画师页、标签搜索、系列进小说系列、书签增删）。
 */
class NovelMarkersFeedFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        // 零捕获：source 无参，自持一个 NovelMarkersRepo，不碰 Fragment / View。
        NovelMarkersFeedSource()
    }

    /**
     * 封面 + 头像的 Glide 请求管理器，建一次复用（对齐 MutedUserFeedFragment.userGlide）：
     * bind 加载 / recycle 清理都走它，避免每处 `Glide.with(view)` 递归找承载 fragment。
     */
    private val rowGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(rowGlide)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.core_string_novel_marker)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(novelMarkerRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐 legacy ListFragment 的 LinearItemDecoration(12dp)。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    private fun novelMarkerRenderer() = feedRenderer<NovelMarkerFeedItem, RecyNovelMarkersBinding>(
        inflate = RecyNovelMarkersBinding::inflate,
        // 点击监听只在 onCreate 注册一次，绑定时零 lambda 分配；点击那一刻读 cell.item 拿当下条目。
        create = { cell ->
            cell.binding.root.setOnClick { openNovel(cell.item.marker) }
            cell.binding.cover.setOnClick { openCover(cell.item.marker) }
            cell.binding.userHead.setOnClick { openUser(cell.item.marker) }
            cell.binding.author.setOnClick { openUser(cell.item.marker) }
            cell.binding.series.setOnClick { openSeries(cell.item.marker) }
            cell.binding.mark.setOnClick {
                val marker = cell.item.marker
                PixivOperate.postNovelMarker(marker.novel_marker, marker.novel.id, cell.binding.mark)
            }
            cell.binding.novelTag.setOnTagClickListener { _, position, _ ->
                openTagSearch(cell.item.marker, position)
                true
            }
        },
        recycle = { cell ->
            rowGlide.clear(cell.binding.cover)
            rowGlide.clear(cell.binding.userHead)
        },
    ) { cell -> bindRow(cell) }

    private fun bindRow(cell: FeedCell<NovelMarkerFeedItem, RecyNovelMarkersBinding>) {
        val b = cell.binding
        val novel = cell.item.marker.novel
        val seriesTitle = novel.series?.title
        if (!seriesTitle.isNullOrEmpty()) {
            b.series.visibility = View.VISIBLE
            b.series.text = getString(R.string.string_184, seriesTitle)
        } else {
            b.series.visibility = View.GONE
        }
        b.title.text = novel.title
        b.date.text = novel.create_date?.take(10).orEmpty()
        // user / image_urls 是 legacy bean 的 Java 字段（Kotlin 看到的是平台类型），
        // 服务端 marked_novels 正常都会带，但脏数据不该把整页 fling 崩掉——一律走安全调用。
        b.author.text = novel.user?.name.orEmpty()
        b.howManyWord.text = String.format(Locale.getDefault(), "%d字", novel.text_length)
        b.bookmarkCount.text = novel.total_bookmarks.toString()
        b.novelTag.setAdapter(object : TagAdapter<TagsBean>(novel.tags.orEmpty()) {
            override fun getView(parent: FlowLayout, position: Int, tag: TagsBean): View {
                val tv = LayoutInflater.from(parent.context)
                    .inflate(R.layout.recy_single_line_text_new, parent, false) as TextView
                tv.text = tag.name
                return tv
            }
        })
        rowGlide.load(GlideUtil.getUrl(novel.image_urls?.maxImage)).into(b.cover)
        rowGlide.load(GlideUtil.getHead(novel.user)).into(b.userHead)
    }

    /** 整卡点击：进小说详情（携带整份 NovelBean，隐藏状态栏），与 legacy itemView 点击一字不差。 */
    private fun openNovel(marker: MarkedNovelItem) {
        if (requireContext().tryOpenNovelReaderDirect(marker.novel.id.toLong())) return
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(Params.CONTENT, marker.novel)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
        intent.putExtra("hideStatusBar", true)
        startActivity(intent)
    }

    /** 封面点击：看大图（原图 URL 走图片详情）。 */
    private fun openCover(marker: MarkedNovelItem) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(Params.URL, GlideUtil.getUrl(marker.novel.image_urls.maxImage).toStringUrl())
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情")
        startActivity(intent)
    }

    /** 头像 / 作者名点击：进画师页。 */
    private fun openUser(marker: MarkedNovelItem) {
        val intent = Intent(requireContext(), UActivity::class.java)
        intent.putExtra(Params.USER_ID, marker.novel.user.id)
        startActivity(intent)
    }

    /** 系列点击：进小说系列页（series id 走 [NovelSeriesFragment.ARG_SERIES_ID]，long）。 */
    private fun openSeries(marker: MarkedNovelItem) {
        val series = marker.novel.series ?: return
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(NovelSeriesFragment.ARG_SERIES_ID, series.id.toLong())
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列")
        startActivity(intent)
    }

    /** 标签点击：按标签搜索（INDEX=1 小说档）。 */
    private fun openTagSearch(marker: MarkedNovelItem, position: Int) {
        val intent = Intent(requireContext(), SearchActivity::class.java)
        intent.putExtra(Params.KEY_WORD, marker.novel.tags[position].name)
        intent.putExtra(Params.INDEX, 1)
        startActivity(intent)
    }
}

/**
 * 书签条目：持 legacy [MarkedNovelItem]（网络下行的 marked_novels 单条）。
 * feedKey 用小说 id（[ceui.lisa.models.NovelBean.getId] 返回 int，同类内唯一稳定）。
 */
class NovelMarkerFeedItem(val marker: MarkedNovelItem) : FeedItem {
    override val feedKey: Any get() = marker.novel.id
}

/**
 * 小说书签数据源：包裹既有的 [NovelMarkersRepo]，把 Rx→suspend 桥一下（对齐 SearchIllustFeedSource）。
 * load(null) → getNovelMarkers；load(cursor) → setNextUrl + getNextNovelMarkers；过滤走 repo 自己的
 * mapper()（默认 [ceui.lisa.core.Mapper]，与 legacy `.map(mFunction)` 同一条流水线，对小说列表实为
 * 空操作但保持链路一致）。网络请求前的同步重活切 IO，映射 / 建条目切 Default。游标 = nextUrl。
 *
 * 零 Fragment 捕获：无参构造，自持 repo，不碰 View / Context。
 */
class NovelMarkersFeedSource : FeedSource<String> {

    private val repo = NovelMarkersRepo()

    override suspend fun load(cursor: String?): FeedPage<String> {
        // initApi / initNextApi 在返回 Observable 前是纯同步的（Retro 组装请求），放 IO 稳妥；
        // 真正的挂起在 awaitFirstValue 内部（subscribeOn(io) + firstOrError）。
        val resp: ListNovelMarkers = if (cursor == null) {
            withContext(Dispatchers.IO) { repo.initApi() }.awaitFirstValue()
        } else {
            withContext(Dispatchers.IO) {
                repo.setNextUrl(cursor)
                repo.initNextApi()
            }.awaitFirstValue()
        }
        // 默认 Mapper 只过滤 IllustsBean/NovelBean，对 MarkedNovelItem 是 no-op → 不套，直接建条目
        // （去掉多余的未受检 cast + Default 线程切换 + 全量空遍历）。
        val items: List<FeedItem> = resp.list.orEmpty().map { NovelMarkerFeedItem(it) }
        return FeedPage(items, resp.nextUrl?.takeIf { it.isNotEmpty() })
    }
}
