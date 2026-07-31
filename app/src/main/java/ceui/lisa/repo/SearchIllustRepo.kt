package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.ui.prime.PrimeIllustLoader
import ceui.pixiv.ui.search.SortType
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.ui.search.v3.SearchTarget
import io.reactivex.Observable
import io.reactivex.functions.Function
import java.time.LocalDate

class SearchIllustRepo @JvmOverloads constructor(
    var keyword: String?,
    private var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    //var isPopular: Boolean,
    private var isPremium: Boolean?,
    private var startDate: String?,
    private var endDate: String?,
    private var r18Restriction: Int?,
    // V3 filter 字段——legacy FragmentFilter 没用过；V3 sheet 经
    // SearchFilterV3LegacyBridge 写到 SearchModel，再透到 retrofit。
    private var bookmarkMin: Int? = null,
    private var tool: String? = null,
    private var lang: String? = null,
    private var searchAiType: Int? = null,
    private var ratioPattern: String? = null,
    // 分辨率档位 4 项 —— V3 sheet 写入，老 FragmentFilter 不暴露
    private var widthMin: Int? = null,
    private var widthMax: Int? = null,
    private var heightMin: Int? = null,
    private var heightMax: Int? = null,
    /**
     * 投稿期间相对预设档（[DurationBucket].name 字串形式）—— V3 sheet 写入。
     * 与 [startDate]/[endDate] 互斥：非 null 时 [initApi] 当场用 today−N 算出真实 start/end_date
     * 覆盖发出去，跨午夜也不会窗口停滞。null 时直接用 [startDate]/[endDate]（指定期间自定义）。
     */
    private var durationBucket: String? = null,
    // 作品类别（仅 illust/manga）—— content_type query 参数；null = 不传(默认档等价)。
    // 放最尾靠 @JvmOverloads 的默认值兜底 positional 构造：feeds 版 [ceui.pixiv.ui.search.SearchIllustFeedSource]
    // 用 SearchIllustRepo(null×8) 构造后靠 update(searchModel) 填参，也依赖这些默认值。
    private var contentType: String? = null,
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    override fun initApi(): Observable<ListIllust> {
        if (sortType == PixivSearchParamUtil.TRENDING_BUILTIN_SORT_VALUE) {
            return loadTrendingBuiltinIllusts()
        }
        // 关键字写搜索历史已上移到 SearchActivity（首搜 initModel + 重搜 nowGo，按 id 去重收口）。
        // 不再寄生在这里——原来会被上面的 trending_builtin 提前 return 跳过，且只有插画 tab 触发，
        // 小说/作者 tab 与「内置热门榜」排序都漏写。

        // 收藏量两条桶并存：
        //  - bookmarkMin 走官方 `bookmark_num_min` query 参数（仅会员 popular 生效）
        //  - starSize（"Xusers入り"）作为关键字后缀拼到 query 里（对非会员有效，命中 pixiv
        //    自动桶标签）
        // 两者来自 V3 sheet 的两个独立维度（bookmarkBucket / keywordUsersBucket），用户可同时设置。
        val keywordSuffix = if (TextUtils.isEmpty(starSize)) "" else " $starSize"
        // R18 三档不再拼 -R-18 / R-18 关键字（hack 匹配字面标签会让全年龄/R 混在一起）；
        // 改由 FilterMapper.setSearchR18Restriction 按真实 x_restrict 客户端过滤（见 update()）。
        val assembledKeyword: String = (keyword + keywordSuffix).trim()

        // 路由 sort：
        //  - popular_preview 是 popular-preview endpoint 专属——/v1/search/illust 收到会 400
        //  - popular_desc / popular_male_desc / popular_female_desc 非 premium：pixiv 旧约束，
        //    非付费用户不能用人气系列 sort，需走 popular-preview。男女向两档（issue #575）
        //    平时被 V3 sheet gate 住非会员看不到，这里再兜一层防御。
        //  其余值（date_desc / date_asc / popular_*-premium）走 /v1/search/illust，sort 透传。
        val usePopularPreview = sortType == SortType.POPULAR_PREVIEW ||
                (isPremium != true && (
                    sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                    sortType == SortType.POPULAR_MALE_DESC ||
                    sortType == SortType.POPULAR_FEMALE_DESC
                ))

        // 投稿期间相对档当场算 today−N（每次 initApi 都重算,跨午夜窗口自动跟随今天）;
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        // 默认档「标签部分一致」不传 search_target，让标题命中也能搜到（#906）——
        // 见 [SearchTarget.toQueryValue] 注释。
        val effectiveSearchTarget = SearchTarget.toQueryValue(searchType)

        return if (usePopularPreview) {
            Retro.getAppApi().popularPreview(
                assembledKeyword,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                tool,
                lang,
                searchAiType,
                ratioPattern,
                contentType,
                widthMin,
                widthMax,
                heightMin,
                heightMax,
            )
        } else {
            Retro.getAppApi().searchIllust(
                assembledKeyword,
                sortType,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                tool,
                lang,
                searchAiType,
                ratioPattern,
                contentType,
                widthMin,
                widthMax,
                heightMin,
                heightMax,
            )
        }
    }

    /**
     * 投稿期间 → (start_date, end_date)：
     *   - bucket 非空：今日往前推 N 天/月/年（[DurationBucket.toDateRange]）
     *   - bucket 为空：回落到 [startDate]/[endDate] 原值（V3「指定期间」自定义）
     *   - bucket 名称无效：当作空 bucket 处理（fail-safe）
     */
    private fun resolveDateRange(): Pair<String?, String?> {
        val bucket = durationBucket?.let { name ->
            DurationBucket.values().firstOrNull { it.name == name }
        } ?: return startDate to endDate
        return bucket.toDateRange(LocalDate.now())
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        if (this.filterMapper == null) {
            this.filterMapper = FilterMapper().enableFilterStarSize()
        }
        return this.filterMapper!!
    }

    private fun loadTrendingBuiltinIllusts(): Observable<ListIllust> {
        val result = PrimeIllustLoader.loadForKeyword(keyword)
        if (result != null) {
            return Observable.just(result)
        }
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()
        return Retro.getAppApi().popularPreview(
            keyword ?: "", effectiveStartDate, effectiveEndDate,
            SearchTarget.toQueryValue(searchType),
            bookmarkMin, tool, lang, searchAiType, ratioPattern, contentType,
            widthMin, widthMax, heightMin, heightMax,
        )
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        //isPopular = pop
        isPremium = searchModel.isPremium.value
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value
        r18Restriction = searchModel.r18Restriction.value
        bookmarkMin = searchModel.bookmarkMin.value
        tool = searchModel.tool.value
        lang = searchModel.lang.value
        ratioPattern = searchModel.ratioPattern.value
        contentType = searchModel.contentType.value
        widthMin = searchModel.widthMin.value
        widthMax = searchModel.widthMax.value
        heightMin = searchModel.heightMin.value
        heightMax = searchModel.heightMax.value
        durationBucket = searchModel.durationBucket.value
        // AI：屏蔽走全局 isDeleteAIIllust → search_ai_type=1；「仅看 AI」会话态（issue #909）→
        // 服务端全返(search_ai_type=0)，再由 FilterMapper 客户端按 illust_ai_type==2 筛。
        val onlyAi = searchModel.onlyAi.value == true
        searchAiType = if (onlyAi) 0 else if (Shaft.sSettings.isDeleteAIIllust) 1 else 0

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
        // R18 三档（0=不限/1=仅安全/2=仅R-18）→ 客户端按 x_restrict 过滤
        this.filterMapper?.setSearchR18Restriction(r18Restriction ?: 0)
        this.filterMapper?.setSearchOnlyAi(onlyAi)
    }

    private fun getStarSizeLimit(): Int {
        // 客户端二次兜底过滤：取两条桶里较高的那个门槛。
        // bookmarkMin 来自官方 query；starSize 是 "Xusers入り" 关键字后缀。两条独立，可同存。
        val fromQuery = bookmarkMin ?: 0
        val fromStar = if (TextUtils.isEmpty(starSize)) 0
        else Regex("""\d+""").find(starSize!!)?.value?.toIntOrNull() ?: 0
        return maxOf(fromQuery, fromStar)
    }
}
