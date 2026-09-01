package ceui.pixiv.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.db.mirror.BookmarkAuthorFacet
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.BookmarkTagFacet
import ceui.pixiv.db.mirror.BookmarkYearFacet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 收藏库页的状态中枢。
 *
 * **筛选条件归 VM**（对齐 `LikeIllustFilterViewModel` 的约定）：旋转 / 视图重建后
 * 用户辛苦调出来的筛选不丢，[BookmarkLibraryFeedSource] 也只从这里读，
 * 不必捕获 Fragment。
 *
 * 计数与 facet 是**独立于列表**的查询：它们要回答的是「这套条件一共命中多少件」
 * 和「还能往下钻哪些标签」，而列表只加载看得见的那几十条。分开跑，列表才不会
 * 为了显示一个总数去把三万行全捞出来。
 */
class BookmarkLibraryViewModel : ViewModel() {

    /** 已经绑定过书架没有。sheet 用它防御「VM 还没 bind 就被恢复出来」的时序（见那边）。 */
    var bound = false
        private set

    lateinit var shelf: BookmarkShelf
        private set

    private val _filter = MutableStateFlow(BookmarkFilter(shelfKey = ""))
    val filter: StateFlow<BookmarkFilter> = _filter.asStateFlow()

    /** 当前筛选命中的件数；null = 还在算。 */
    private val _resultCount = MutableStateFlow<Int?>(null)
    val resultCount: StateFlow<Int?> = _resultCount.asStateFlow()

    /** 这个书架本地一共镜像了多少件（不受筛选影响）。 */
    private val _totalCount = MutableStateFlow<Int?>(null)
    val totalCount: StateFlow<Int?> = _totalCount.asStateFlow()

    /** 后台镜像的同步状态（进度条 / 「还在补齐」提示）。 */
    private val _mirrorState = MutableStateFlow<BookmarkMirrorStateEntity?>(null)
    val mirrorState: StateFlow<BookmarkMirrorStateEntity?> = _mirrorState.asStateFlow()

    private val _tagFacets = MutableStateFlow<List<BookmarkTagFacet>>(emptyList())
    val tagFacets: StateFlow<List<BookmarkTagFacet>> = _tagFacets.asStateFlow()

    private val _authorFacets = MutableStateFlow<List<BookmarkAuthorFacet>>(emptyList())
    val authorFacets: StateFlow<List<BookmarkAuthorFacet>> = _authorFacets.asStateFlow()

    private val _yearFacets = MutableStateFlow<List<BookmarkYearFacet>>(emptyList())
    val yearFacets: StateFlow<List<BookmarkYearFacet>> = _yearFacets.asStateFlow()

    /** 单飞：筛选条件连着改（用户在 sheet 里一路点）时只留最后一次的查询。 */
    private var countJob: Job? = null
    private var facetJob: Job? = null

    /** 幂等绑定：Fragment 与 FeedSource 都会调，谁先谁后都行。 */
    fun bind(shelf: BookmarkShelf) {
        if (bound) return
        bound = true
        this.shelf = shelf
        _filter.value = BookmarkFilter(shelfKey = shelf.key)
        Timber.tag(TAG).d("绑定书架 %s", shelf.label)
        refreshCounts()
        refreshFacets()
        refreshYearFacets()
    }

    /**
     * 就地换一个书架（公开 ↔ 悄悄收藏）。返回 true 表示真的换了。
     *
     * **排序保留、筛选条件清空**：排序是「我想怎么看」，换个书架依然成立；而标签 / 作者 /
     * 年份都是从**这个书架**的内容里统计出来的，带到另一个书架上多半是一条都命中不了的死条件
     * ——用户会看见一个空列表却不知道为什么。关键词同理（多半是刚才那批内容里的词），一并清掉。
     */
    fun switchShelf(next: BookmarkShelf): Boolean {
        if (bound && shelf == next) return false
        bound = true
        shelf = next
        _filter.value = BookmarkFilter(
            shelfKey = next.key,
            sort = _filter.value.sort,
            randomSeed = _filter.value.randomSeed,
        )
        Timber.tag(TAG).d("切换到书架 %s", next.label)
        _resultCount.value = null
        _totalCount.value = null
        _tagFacets.value = emptyList()
        _authorFacets.value = emptyList()
        _yearFacets.value = emptyList()
        refreshCounts()
        refreshFacets()
        refreshYearFacets()
        return true
    }

    /**
     * 改筛选条件。返回 true 表示真的变了（调用方据此决定要不要重刷列表）——
     * sheet 里点一下又点回来是很常见的，白刷一次列表会让滚动位置无谓地跳回顶部。
     */
    fun updateFilter(transform: (BookmarkFilter) -> BookmarkFilter): Boolean {
        val old = _filter.value
        val next = transform(old)
        if (next == old) return false
        _filter.value = next
        Timber.tag(TAG).d(
            "筛选变更 sort=%s kw='%s' tags=%d 排除=%d 作者=%d 类型=%s",
            next.sort, next.keyword, next.tagNames.size, next.excludedTagNames.size,
            next.authorIds.size, next.workTypes,
        )
        refreshCounts()
        refreshFacets()
        return true
    }

    /** 清空全部条件，只留书架与排序（排序是「我想怎么看」，不是「我要看哪些」）。 */
    fun clearConditions(): Boolean = updateFilter { current ->
        BookmarkFilter(shelfKey = current.shelfKey, sort = current.sort, randomSeed = current.randomSeed)
    }

    fun setMirrorState(state: BookmarkMirrorStateEntity?) {
        _mirrorState.value = state
    }

    /**
     * 后台又镜像进来一批：命中数、总数、年份分布都得跟着变。
     * 这是**唯一**该重算年份分布的时机（见 [refreshYearFacets]）。
     */
    fun onMirrorChanged() {
        refreshCounts()
        refreshYearFacets()
    }

    /** 只重算「当前条件命中多少 / 库里一共多少」。筛选变更走这条，年份分布不掺进来。 */
    fun refreshCounts() {
        val current = _filter.value
        if (current.shelfKey.isEmpty()) return
        countJob?.cancel()
        countJob = viewModelScope.launch {
            try {
                _resultCount.value = BookmarkLibraryRepo.count(current)
                _totalCount.value = BookmarkLibraryRepo.totalRows(current.shelfKey)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 计数只服务标题栏上的一个数字，失败了显示「—」就够，不该把页面搞崩
                Timber.tag(TAG).w(t, "计数失败")
                _resultCount.value = null
            }
        }
    }

    /**
     * 年份分布。**刻意不放进 [refreshCounts]**：它是 `strftime` + GROUP BY 的全书架扫描，
     * 却只跟「库里有哪些年份的作品」有关，跟当前筛选条件毫无关系 —— 挂在筛选路径上的话，
     * 用户在面板里每点一下 chip 都要白扫一遍全表。只在绑定时和镜像行数变化时跑。
     */
    private var yearJob: Job? = null
    private fun refreshYearFacets() {
        val shelfKey = _filter.value.shelfKey
        if (shelfKey.isEmpty()) return
        yearJob?.cancel()
        yearJob = viewModelScope.launch {
            try {
                _yearFacets.value = BookmarkLibraryRepo.yearFacets(shelfKey)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "年份分布统计失败")
            }
        }
    }

    private fun refreshFacets() {
        val current = _filter.value
        if (current.shelfKey.isEmpty()) return
        facetJob?.cancel()
        facetJob = viewModelScope.launch {
            try {
                _tagFacets.value = BookmarkLibraryRepo.tagFacets(current)
                _authorFacets.value = BookmarkLibraryRepo.authorFacets(current)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "facet 统计失败")
            }
        }
    }

    private companion object {
        const val TAG = "BookmarkLibrary"
    }
}
