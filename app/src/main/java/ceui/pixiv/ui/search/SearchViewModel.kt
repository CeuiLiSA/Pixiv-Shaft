package ceui.pixiv.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Tag
import ceui.pixiv.api.model.ObjectType
import ceui.pixiv.ui.search.v3.BodyLengthUnit
import ceui.pixiv.ui.search.v3.IllustContentType
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.ui.search.v3.SearchOptionsResponse
import ceui.pixiv.utils.Event

class SearchViewModel(initialKeyword: String) : ViewModel() {


    val tagList = MutableLiveData<List<Tag>>()

    /** 兼容老的 radio_tab UI——存的是 0..3 索引；写时同步到 [illustFilter]/[novelFilter] 的 sort。
     *  初始 3 = 「按热度」对齐 [SearchFilterV3] 的默认 [SortType.POPULAR_DESC]，避免首帧高亮闪一下。 */
    val illustSelectedRadioTabIndex = MutableLiveData(3)
    val novelSelectedRadioTabIndex = MutableLiveData(3)

    /**
     * V3 Filter 的真单源——sort/bookmark/tool/lang/genre/duration/dates/ai/r18 全在这里。
     * 初始值读 [Shaft.sSettings] 里用户配的「搜索默认排序方式 / 搜索结果收藏量筛选 /
     * 不显示 AI 作品」三项，对齐老 [ceui.lisa.fragments.FragmentFilter] 行为。
     */
    val illustFilter = MutableLiveData(SearchFilterV3.fromGlobalDefaults())
    val novelFilter = MutableLiveData(SearchFilterV3.fromGlobalDefaults(forNovel = true))

    /** /v1/search/options 的缓存——拉一次给 illust + novel 共用。 */
    val searchOptions = MutableLiveData<SearchOptionsResponse?>()

    val inputDraft = MutableLiveData("")

    init {
        if (initialKeyword.isNotEmpty()) {
            tagList.value = listOf(Tag(initialKeyword))
        }
    }

    private val _searchIllustMangaEvent = MutableLiveData<Event<Long>>()
    private val _searchUserEvent = MutableLiveData<Event<Long>>()
    private val _searchNovelEvent = MutableLiveData<Event<Long>>()

    val searchIllustMangaEvent: LiveData<Event<Long>> = _searchIllustMangaEvent

    fun triggerSearchIllustMangaEvent(index: Long) {
        _searchIllustMangaEvent.postValue(Event(index))
    }


    val searchUserEvent: LiveData<Event<Long>> = _searchUserEvent

    fun triggerSearchUserEvent(index: Long) {
        _searchUserEvent.postValue(Event(index))
    }


    val searchNovelEvent: LiveData<Event<Long>> = _searchNovelEvent

    fun triggerSearchNovelEvent(index: Long) {
        _searchNovelEvent.postValue(Event(index))
    }

    fun triggerAllRefreshEvent() {
        val now = System.currentTimeMillis()
        triggerSearchIllustMangaEvent(now)
        triggerSearchUserEvent(now)
        triggerSearchNovelEvent(now)
    }

    /** Radio tab 索引 → SortType。和 V3 Filter 的 sort 双向同步用。 */
    fun radioIndexToSort(index: Int): String = when (index) {
        0 -> SortType.POPULAR_PREVIEW
        1 -> SortType.DATE_DESC
        2 -> SortType.DATE_ASC
        3 -> SortType.POPULAR_DESC
        else -> SortType.POPULAR_PREVIEW
    }

    /** SortType → Radio tab 索引。trending_builtin 等 radio 没有的项落回热度预览。 */
    fun sortToRadioIndex(sort: String): Int = when (sort) {
        SortType.POPULAR_PREVIEW -> 0
        SortType.DATE_DESC -> 1
        SortType.DATE_ASC -> 2
        SortType.POPULAR_DESC -> 3
        else -> 0
    }

    /**
     * 用 V3 Filter + tagList 拼最终 SearchConfig。
     *
     * - keyword：原始 tagList，不再追加任何 R18 后缀。R18 三档（全部/仅安全/仅R-18）改由
     *   [SearchConfig.r18Mode] 带到 DataSource，按作品真实 `x_restrict` 客户端过滤——旧版
     *   `-R-18` / `R-18` 关键字 hack 匹配字面标签，会让全年龄和 R 混在一起。
     * - usersYori 留空：V3 走 bookmark_num_min/max API 参数，不再追加「Xusers入り」关键字
     */
    fun buildSearchConfig(@Suppress("UNUSED_PARAMETER") usersYori: Int?, objectType: String): SearchConfig {
        val isNovel = objectType == ObjectType.NOVEL
        val filter = (if (isNovel) novelFilter.value else illustFilter.value) ?: SearchFilterV3()
        val keyword = tagList.value?.joinToString(separator = " ") { it.name ?: "" }.orEmpty()
        // 投稿期间 3 选 1（互斥）：
        //   1) durationBucket 非空：当场用 today−N 算 start/end_date
        //   2) 否则用 filter.startDate/endDate（指定期间自定义）
        //   3) 都空：不传，对应 iOS「不限」
        // hoist 出 LocalDate.now() 只算一次,免得同一句里调两次
        val computedDates = filter.durationBucket?.toDateRange(java.time.LocalDate.now())
        return SearchConfig(
            keyword = keyword,
            sort = filter.sort,
            search_target = filter.searchTarget.apiValue,
            bookmarkMin = filter.bookmarkRange?.min,
            bookmarkMax = filter.bookmarkRange?.max,
            tool = if (isNovel) null else filter.tool,
            genre = if (isNovel) filter.genre else null,
            lang = filter.lang,
            startDate = computedDates?.first ?: filter.startDate,
            endDate = computedDates?.second ?: filter.endDate,
            searchAiType = filter.aiMode.searchAiType(),
            // 「仅看 AI」官方无参数，带到 DataSource 客户端按 illust/novel_ai_type 过滤
            aiMode = filter.aiMode,
            // novel-only switches —— illust 路径忽略；nullable 保留 retrofit 不传 query 的语义
            isOriginalOnly = if (isNovel && filter.isOriginalOnly) true else null,
            isReplaceableOnly = if (isNovel && filter.isReplaceableOnly) true else null,
            // 长宽比仅 illust/manga 维度；novel endpoint 不识别 ratio_pattern
            ratioPattern = if (isNovel) null else filter.ratioPattern?.apiValue,
            // 作品类别仅 illust/manga；默认档「插画、漫画、动图」等价于不传 content_type
            contentType = if (isNovel || filter.contentType == IllustContentType.IllustAndMangaAndUgoira) null
                else filter.contentType.apiValue,
            // 分辨率仅 illust/manga；4 个 query 参数从 bucket 派生
            widthMin = if (isNovel) null else filter.resolutionBucket?.widthMin,
            widthMax = if (isNovel) null else filter.resolutionBucket?.widthMax,
            heightMin = if (isNovel) null else filter.resolutionBucket?.heightMin,
            heightMax = if (isNovel) null else filter.resolutionBucket?.heightMax,
            // 正文长度仅 novel；按 unit 三选一分派到对应 query 组（其余两组保持 null）
            textLengthMin = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Char)
                filter.bodyLength.min else null,
            textLengthMax = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Char)
                filter.bodyLength.max else null,
            wordCountMin = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Word)
                filter.bodyLength.min else null,
            wordCountMax = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Word)
                filter.bodyLength.max else null,
            readingTimeMin = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.ReadingTime)
                filter.bodyLength.min else null,
            readingTimeMax = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.ReadingTime)
                filter.bodyLength.max else null,
            // R18 三档带到 DataSource，按 x_restrict 客户端过滤
            r18Mode = filter.r18Mode,
        )
    }
}
