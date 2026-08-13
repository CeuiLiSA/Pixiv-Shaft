package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.activities.Shaft
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.SearchNovelRepo
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.awaitFirstValue
import io.reactivex.functions.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索「小说」tab（feeds 框架版，替代 legacy FragmentSearchNovel + SearchNovelRepo + NAdapter）。
 * 卡片复用 [NovelFeedFragment]。数据源包裹既有 [SearchNovelRepo]（无损、零发散，同插画 tab 思路），
 * 过滤走 repo 自己的 Mapper（含搜索 R18 三态 + 仅看 AI），过滤后 bean→loxia Novel 建条目。
 *
 * 响应式重搜：observe nowGo → 命中小说标签匹配档才 refresh（对齐 legacy TAG_MATCH_VALUE_NOVEL guard）。
 */
class SearchNovelFeedFragment : NovelFeedFragment() {

    private var searchRefreshPending = false

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        SearchNovelFeedSource(searchModel)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchModel.nowGo.observe(viewLifecycleOwner) {
            if (PixivSearchParamUtil.TAG_MATCH_VALUE_NOVEL.contains(searchModel.searchType.value)) {
                // 离屏 ViewPager 页处于 STARTED 也会收到 LiveData；不在后台搜索，
                // 只记一次待刷新，等这个 tab 真正 RESUMED 时再执行。
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    searchRefreshPending = false
                    feedViewModel.refresh()
                } else {
                    searchRefreshPending = true
                }
            } else {
                searchRefreshPending = false
            }
        }
    }

    override fun onResume() {
        val hadExistingLoad = feedViewModel.uiState.value.let {
            it.hasLoadedOnce || it.refresh is LoadState.Loading
        }
        super.onResume()
        if (searchRefreshPending) {
            searchRefreshPending = false
            // 首次进入由 FeedFragment.ensureLoaded() 加载最新条件；只有旧数据或
            // 旧请求存在时才显式刷新，避免首次进入连续发两次。
            if (hadExistingLoad) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchNovelFeedFragment = SearchNovelFeedFragment()
    }
}

/** 搜索小说数据源：包裹 [SearchNovelRepo]（同插画 tab）。过滤后 NovelBean→loxia Novel 建条目。 */
class SearchNovelFeedSource(private val searchModel: SearchModel) : FeedSource<String> {

    private var repo: SearchNovelRepo? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        val r = repo ?: SearchNovelRepo(null, null, null, null, null, null, null, null).also { repo = it }
        val list: ListNovel = if (cursor == null) {
            r.update(searchModel)
            r.initApi().awaitFirstValue()
        } else {
            r.setNextUrl(cursor)
            r.initNextApi().awaitFirstValue()
        }
        val items = withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            val filtered = (r.mapper() as Function<ListNovel, ListNovel>).apply(list)
            // Mapper 已做完搜索专属过滤（R18 三态 / 仅看 AI），直接 bean→loxia Novel 建条目，不再过滤。
            filtered.list.orEmpty().mapNotNull { rawNovelItem(it) }
        }
        return FeedPage(items, list.nextUrl?.takeIf { it.isNotEmpty() })
    }

    private fun rawNovelItem(bean: NovelBean): NovelFeedItem? {
        val novel = runCatching {
            Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(bean), Novel::class.java)
        }.getOrNull() ?: return null
        return NovelFeedItem(novel)
    }
}
