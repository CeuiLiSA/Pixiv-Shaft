package ceui.pixiv.ui.collection

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CancellationException
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentBookedTagFeedBinding
import ceui.lisa.databinding.RecyBookTagBinding
import ceui.lisa.models.TagsBean
import ceui.lisa.repo.BookedTagRepo
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * 「按标签筛选」——按收藏标签浏览收藏(feeds 框架版,替代 legacy [ceui.lisa.fragments.FragmentBookedTag]
 * + BookedTagAdapter + NetListFragment)。入口在 [TemplateActivity] `EXTRA_FRAGMENT="按标签筛选"`。
 *
 * 复刻 legacy 全部行为:
 * 1. 首屏顶部两个虚拟行 `[未分類, 全部]`(count=-1),由数据源拼在真实标签前(见 [BookedTagFeedSource])。
 * 2. 客户端搜索:搜索框 debounce 200ms(清空 0ms),按 name/translated_name 大小写不敏感 contains
 *    过滤;搜索时隐藏虚拟行(count==-1),空结果走框架空态;搜索时禁用下拉刷新。
 * 3. 预加载全部:legacy 是搜索时才逐页拉全;收藏标签是有界集合,这里更简单——首屏 load() 一次拉全
 *    ([BookedTagFeedSource]),搜索直接命中内存全量。因此 legacy 的 searchPreloadProgress 进度条
 *    在此常隐藏(首屏拉全由框架 loading 圈反馈),布局保留其 id 只为结构对齐。
 * 4. 行点击:发 [Params.FILTER_NOVEL]/[Params.FILTER_ILLUST] 广播(CONTENT=tag.name,
 *    STAR_TYPE=starType),然后 finish()。
 * 5. toolbar 菜单「同义词词典」仅在 [Shaft] 设置开启时显示。
 *
 * 参数:`type`([Params.DATA_TYPE],0 插画/1 小说)、`starType`([Params.STAR_TYPE],公开/私人收藏)。
 */
class BookedTagFeedFragment : FeedFragment(R.layout.fragment_booked_tag_feed) {

    private val binding by viewBinding(FragmentBookedTagFeedBinding::bind)

    // 渲染器 / 行点击广播要用(渲染器由视图作用域持有,捕获 Fragment 安全——零捕获约定只约束
    // 被 VM 长期持有的 FeedSource lambda)。lazy 首次访问在 super.onViewCreated 装配渲染器时,
    // 此时已 attach,arguments 就绪。
    private val type: Int by lazy { requireArguments().getInt(Params.DATA_TYPE, 0) }
    private val starType: String? by lazy { requireArguments().getString(Params.STAR_TYPE) }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获:arguments 先读进局部 val,数据源只持有两个基本类型参数(sourceProvider lambda
        // 本身仅在建 VM 时跑一次、不被 VM 保留,这里引用 this 是安全的短命捕获)。
        val args = requireArguments()
        val type = args.getInt(Params.DATA_TYPE, 0)
        val starType = args.getString(Params.STAR_TYPE)
        BookedTagFeedSource(type, starType)
    }

    /** 全量已在首屏一次拉全,无翻页;关掉滚到底自动追加(nextCursor 本就是 null,双保险)。 */
    override val loadMoreEnabled: Boolean = false

    /** 过滤真源:每代真实数据落地时刷新为 `[虚拟行 + 真实标签]` 全量;搜索从它筛。 */
    private var fullList: List<FeedItem> = emptyList()

    /** 已捕获过的整代代号;只有 refresh 成功(网络首屏)才自增 refreshGeneration,自身 mutateItems 不会。 */
    private var capturedGeneration: Int = 0

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingFilter: Runnable? = null
    private var currentQuery: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.apply {
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }
        binding.toolbarTitle.text = getString(R.string.string_244)

        // 同义词词典管理入口(issue #904):总开关打开时才显示。
        if (Shaft.sSettings.isSynonymDictEnabled) {
            binding.toolbar.menu.add(getString(R.string.synonym_dict_title))
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "同义词词典")
                    })
                    true
                }
        }

        setUpSearch()

        // 配置变更后 VM 可能保留着「上次搜索的过滤子集」(refreshGeneration 已推进)。先把 capturedGeneration
        // 对齐当前值,让下面的 collector 不会把这份陈旧子集误当 fullList 捕获(否则清空搜索只剩子集、全量
        // 再也拿不回);真正的 fullList 由下面 config-change 的 refresh() 拉回的新一代重建。首次创建时 VM
        // 代号还是 0(首拉在飞),不受影响。
        capturedGeneration = feedViewModel.uiState.value.refreshGeneration

        // 捕获每代真实数据(重建 fullList);与基类的渲染 collector 并行,各订各的。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state -> onFeedState(state) }
            }
        }

        // 视图重建(旋转等)时强制重载,理由是 fullList 这份过滤真源只活在 Fragment 里,配置变更后
        // 会丢:此时 VM 被保留、其 items 可能停在「上一次搜索的过滤子集」上,新实例若直接把它当
        // fullList 捕获就再也翻不回全量。refresh() 重新一次性拉全 + onFeedState 清空搜索框,得到干净
        // 全量态——正是 legacy 旋转即重新 onFirstLoaded 的行为(此处刻意让出 feeds「配置变更不重载」
        // 这一优化,换 fullList 镜像的正确性)。首次创建 savedInstanceState 为 null,不重复触发。
        if (savedInstanceState != null) {
            feedViewModel.refresh()
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐 legacy initRecyclerView 的 LinearItemDecoration(dp2px(16))。
        listView.addItemDecoration(LinearItemDecoration(16.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(bookedTagRenderer())
    }

    /**
     * 复刻 BookedTagAdapter.bindData(isMuted=false):
     * name 空→「#全部」;有译名→「#name/译名」;否则→「#name」。count==-1(虚拟行)计数留空,
     * 否则显示「N个作品」。行点击发筛选广播 + finish。
     */
    private fun bookedTagRenderer() = feedRenderer<BookedTagFeedItem, RecyBookTagBinding>(
        inflate = RecyBookTagBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener {
                val tag = cell.item.tag
                val intent = Intent(
                    if (type == 1) Params.FILTER_NOVEL else Params.FILTER_ILLUST,
                ).apply {
                    putExtra(Params.CONTENT, tag.name)
                    putExtra(Params.STAR_TYPE, starType)
                }
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
                activity?.finish()
            }
        },
    ) { cell ->
        val tag = cell.item.tag
        val b = cell.binding
        when {
            tag.name.isNullOrEmpty() -> b.starSize.setText(R.string.string_155)
            !tag.translated_name.isNullOrEmpty() ->
                b.starSize.text = String.format("#%s/%s", tag.name, tag.translated_name)
            else -> b.starSize.text = String.format("#%s", tag.name)
        }
        if (tag.count == -1) {
            b.illustCount.text = ""
        } else {
            b.illustCount.text = getString(R.string.string_156, tag.count)
        }
    }

    // ── 客户端搜索 ────────────────────────────────────────────────────────────────

    private fun setUpSearch() {
        binding.searchClear.setOnClickListener { binding.searchInput.setText("") }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                binding.searchClear.isVisible = q.isNotEmpty()
                currentQuery = q
                pendingFilter?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { applyFilter() }
                pendingFilter = runnable
                // 清空立即生效(用户明确意图),否则 debounce 200ms。
                debounceHandler.postDelayed(runnable, if (q.isEmpty()) 0L else 200L)
            }
        })
    }

    private fun applyFilter() {
        // handler 回调可能落在视图销毁之后:onDestroyView 已 removeCallbacks,这里再兜一层。
        if (view == null) return
        val q = currentQuery.lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            // 退出过滤态:恢复下拉刷新,列表还原成全量(fullList === 当前 items 时 mutateItems 免费 no-op)。
            setRefreshEnabled(refreshEnabled)
            val restore = fullList
            feedViewModel.mutateItems { restore }
        } else {
            // 进入过滤态:禁用下拉刷新(对齐 legacy setEnableRefresh(false)),按 name/译名 contains 筛,
            // 隐藏虚拟行(count==-1)。空结果 → items 变空 → 框架自动亮空态。
            setRefreshEnabled(false)
            val filtered = fullList.filter { item ->
                item is BookedTagFeedItem && item.tag.count != -1 && item.tag.matches(q)
            }
            feedViewModel.mutateItems { filtered }
        }
    }

    private fun onFeedState(state: FeedUiState) {
        // 只有 refresh 成功的整代提交才推进 refreshGeneration(mutateItems/loadMore 都不会),
        // 借此把「新一代真实数据落地」和「自身过滤 mutate」区分开,避免过滤后把 fullList 打回空。
        if (state.refreshGeneration != capturedGeneration) {
            capturedGeneration = state.refreshGeneration
            fullList = state.items
            // 对齐 legacy onFirstLoaded:刷新会顺手清掉搜索态(仅在框里有字时清,免得空清触发多余回调)。
            if (!binding.searchInput.text.isNullOrEmpty()) {
                binding.searchInput.setText("")
            }
        }
    }

    override fun onDestroyView() {
        debounceHandler.removeCallbacksAndMessages(null)
        pendingFilter = null
        super.onDestroyView()
    }

    companion object {
        /**
         * @param type 0 插画 / 1 小说([Params.DATA_TYPE])
         * @param starType 公开 / 私人收藏([Params.STAR_TYPE]);TemplateActivity 侧由 EXTRA_KEYWORD 传入
         * (对齐 legacy [ceui.lisa.fragments.FragmentBookedTag.newInstance] 的 EXTRA_KEYWORD→starType 映射)。
         */
        @JvmStatic
        fun newInstance(type: Int, starType: String?): BookedTagFeedFragment {
            return BookedTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.DATA_TYPE, type)
                    putString(Params.STAR_TYPE, starType)
                }
            }
        }
    }
}

/** 标签是否命中查询(name 或 translated_name 大小写不敏感 contains);q 需已 lowercase。 */
private fun TagsBean.matches(lowerQuery: String): Boolean {
    val name = name?.lowercase(Locale.getDefault()).orEmpty()
    val translated = translated_name?.lowercase(Locale.getDefault()).orEmpty()
    return name.contains(lowerQuery) || translated.contains(lowerQuery)
}

/**
 * 收藏标签条目。虚拟行(count==-1)与真实标签用不同前缀 key,保证 DiffUtil 身份唯一
 * (「全部」name="" 与「未分類」也彼此区分)。内容比较靠 data class equals——过滤复用同一份
 * [tag] 实例,同实例即相等,不触发无谓重绑。
 */
data class BookedTagFeedItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any =
        if (tag.count == -1) "virtual:${tag.name}" else "tag:${tag.name}"
}

/**
 * 收藏标签数据源:一次性把所有分页拉全后返回单页(nextCursor 恒 null),让客户端搜索简单且忠实
 * (legacy 搜索时本就要预加载全部,收藏夹标签又是有界集合)。首屏 load() 内:`initApi()` 取第一页,
 * 再循环 `nextUrl = next; initNextApi()` 累积到 nextUrl 空。
 * 拉全后在真实标签前拼两个虚拟行 `[未分類, 全部]`(对齐 legacy onFirstLoaded 的插入顺序)。
 *
 * 防御性上限 [MAX_PAGES]:异常数据(nextUrl 不收敛)时截断并 log,已拉的部分照常可搜。
 *
 * 零 Fragment 捕获:只持有 type/starType 两个基本类型;每次 load 新建 [BookedTagRepo]。
 */
class BookedTagFeedSource(
    private val type: Int,
    private val starType: String?,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            val repo = BookedTagRepo(type, starType)
            val realTags = ArrayList<TagsBean>()
            val first = repo.initApi()
            realTags.addAll(first.list.orEmpty())
            var next: String? = first.nextUrl
            var hops = 0
            while (!next.isNullOrEmpty()) {
                if (hops >= MAX_PAGES) {
                    Timber.w("BookedTagFeedSource: 预加载达页数上限 %d，收藏标签可能未取全", MAX_PAGES)
                    break
                }
                // 某一页失败即止步、保留已加载部分（对齐 legacy preloadOne/finishPreload 的降级：首屏 +
                // 已翻页照常可搜，不因中途某页出错把整页拖成错误态）。首页 initApi 失败仍照常抛出走错误态。
                val page = try {
                    repo.nextUrl = next
                    repo.initNextApi()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Timber.w(e, "BookedTagFeedSource: 预加载第 %d 页失败，保留已加载部分可搜", hops + 2)
                    break
                }
                realTags.addAll(page.list.orEmpty())
                next = page.nextUrl
                hops++
            }
            buildDisplayItems(realTags)
        }
        return FeedPage(items, null)
    }

    /** 真实标签前拼两个虚拟行:先「未分類」后「全部」→ 最终顺序 `[未分類, 全部, ...realTags]`。 */
    private fun buildDisplayItems(realTags: List<TagsBean>): List<FeedItem> {
        val unSeparated = TagsBean().apply {
            count = -1
            name = "未分類"
        }
        val all = TagsBean().apply {
            count = -1
            name = ""
        }
        val result = ArrayList<FeedItem>(realTags.size + 2)
        result.add(BookedTagFeedItem(unSeparated))
        result.add(BookedTagFeedItem(all))
        realTags.forEach { result.add(BookedTagFeedItem(it)) }
        return result
    }

    companion object {
        /** 预加载页数上限,防 nextUrl 不收敛时无限翻页(收藏夹标签实际远小于此)。 */
        private const val MAX_PAGES = 50
    }
}
