package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.actions.Nana7miSearchTelemetry
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.SortType
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.ui.search.v3.SearchTarget
import io.reactivex.Observable
import io.reactivex.functions.Function
import kotlinx.coroutines.runBlocking
import timber.log.Timber
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

    // Repo 实例级借用会话，不切换应用登录态；插画和小说共用同一套刷新/上报规则。
    @Volatile
    private var nana7miSession = Nana7miAccountSession()

    @Volatile
    private var nana7miTelemetry: Nana7miSearchTelemetry.Flow? = null

    override fun initApi(): Observable<ListIllust> {
        // 每轮首屏使用全新会话。即使上一轮请求取消得较晚，它也只能更新旧会话，不能把
        // 旧借用账号重新写进当前查询，污染当前结果的 next_url 翻页。
        val currentNana7miSession = Nana7miAccountSession()
        nana7miSession = currentNana7miSession
        nana7miTelemetry = null
        // 关键字写搜索历史已上移到 SearchActivity（首搜 initModel + 重搜 nowGo，按 id 去重收口）。
        // 不再寄生在这里——原来只有插画 tab 触发，小说/作者 tab 漏写。

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
        val usePopularPreview = sortType == SortType.POPULAR_PREVIEW
        val notPremiumButWantToUsePopularSort = isPremium != true && (
                sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                        sortType == SortType.POPULAR_MALE_DESC ||
                        sortType == SortType.POPULAR_FEMALE_DESC
                )

        // 投稿期间相对档当场算 today−N（每次 initApi 都重算,跨午夜窗口自动跟随今天）;
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        // 默认档「标签部分一致」不传 search_target，让标题命中也能搜到（#906）——
        // 见 [SearchTarget.toQueryValue] 注释。
        val effectiveSearchTarget = SearchTarget.toQueryValue(searchType)
        val requesterUid = SessionManager.loggedInUid
        val telemetry = when {
            usePopularPreview -> Nana7miSearchTelemetry.start(
                requesterUid = requesterUid,
                contentType = Nana7miSearchTelemetry.ContentType.ILLUST,
                query = assembledKeyword,
                initialRoute = Nana7miSearchTelemetry.Route.PREVIEW_DIRECT,
                initialReason = "selected_preview",
            )
            notPremiumButWantToUsePopularSort -> Nana7miSearchTelemetry.start(
                requesterUid = requesterUid,
                contentType = Nana7miSearchTelemetry.ContentType.ILLUST,
                query = assembledKeyword,
                initialRoute = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
            )
            else -> null
        }
        nana7miTelemetry = telemetry

        fun fallbackPreview(reason: String): Observable<ListIllust> {
            telemetry?.fallback(reason)
            Timber.tag(NANA7MI_LOG_TAG).w(
                "stage=route target=popular_preview reason=%s",
                reason,
            )
            val source = Retro.getAppApi().popularPreview(
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
                .doOnNext { response ->
                    Timber.tag(NANA7MI_LOG_TAG).d(
                        "stage=popular_preview result=success illust_count=%d has_next=%s",
                        response.illusts?.size ?: 0,
                        !response.next_url.isNullOrBlank(),
                    )
                }
                .doOnError { error ->
                    Timber.tag(NANA7MI_LOG_TAG).w(
                        error,
                        "stage=popular_preview result=failure error_type=%s",
                        error.javaClass.simpleName,
                    )
                }
            return telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        }

        val result = if (usePopularPreview) {
            val source = Retro.getAppApi().popularPreview(
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
            telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        } else if (notPremiumButWantToUsePopularSort) {
            Nana7miSearchSerial.run("illust_first") { lease ->
                Timber.tag(NANA7MI_LOG_TAG).d(
                    "stage=flow event=start requester_uid=%d sort=%s keyword_length=%d",
                    requesterUid,
                    sortType,
                    assembledKeyword.length,
                )
                lease.blockingObservable {
                    runBlocking {
                        currentNana7miSession.fetchReady()
                    }
                }.flatMap { result ->
                    val newNana7mi = currentNana7miSession.payload
                    if (newNana7mi != null && !newNana7mi.expired) {
                        telemetry?.borrowed(newNana7mi.uid)
                        Timber.tag(NANA7MI_LOG_TAG).d(
                            "stage=route target=official_search account_uid=%d sort=%s",
                            newNana7mi.uid,
                            sortType,
                        )
                        val source = currentNana7miSession.requestWithRefresh(
                            initial = newNana7mi,
                            stage = "official_search",
                            lease = lease,
                            successDetails = { response ->
                                "illust_count=${response.illusts?.size ?: 0} " +
                                        "has_next=${!response.next_url.isNullOrBlank()}"
                            },
                        ) { authorization ->
                            Retro.getAppApi().searchIllustWithAuth(
                                authorization,
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
                        (telemetry?.track(
                            source = source,
                            page = Nana7miSearchTelemetry.Page.FIRST,
                            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                            borrowedUid = newNana7mi.uid,
                        ) ?: source).onErrorResumeNext { error: Throwable ->
                            if (isBorrowedAccountUnavailable(error)) {
                                fallbackPreview("borrowed_refresh_failed")
                            } else {
                                Observable.error(error)
                            }
                        }
                    } else {
                        fallbackPreview(currentNana7miSession.resultLabel(result))
                    }
                }
            }
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
        return telemetry?.observeFirst(result) ?: result
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
        val session = nana7miSession
        val telemetry = nana7miTelemetry
        val payload = session.payload
        // nextUrl 与借用会话必须来自同一轮翻页。串行队列可能让真正订阅延后；若此时
        // 新首屏改写了 RemoteRepo.nextUrl，闭包里再读字段会拼出“旧账号 + 新游标”。
        val nextPageUrl = nextUrl
        return if (session.borrowedAccountLost) {
            endBorrowedPagination("already_lost")
        } else if (payload == null) {
            val source = Retro.getAppApi().getNextIllust(nextPageUrl)
            telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.NEXT,
            ) ?: source
        } else {
            Timber.tag(NANA7MI_LOG_TAG).d(
                "stage=official_search_next event=request account_uid=%d",
                payload.uid,
            )
            Nana7miSearchSerial.run("illust_next") { lease ->
                val source = session.requestWithRefresh(
                    initial = payload,
                    stage = "official_search_next",
                    lease = lease,
                    successDetails = { response ->
                        "illust_count=${response.illusts?.size ?: 0} " +
                                "has_next=${!response.next_url.isNullOrBlank()}"
                    },
                ) { authorization ->
                    Retro.getAppApi().getNextIllustWithAuth(authorization, nextPageUrl)
                }
                (telemetry?.track(
                    source = source,
                    page = Nana7miSearchTelemetry.Page.NEXT,
                    route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                    borrowedUid = payload.uid,
                ) ?: source).onErrorResumeNext { error: Throwable ->
                    if (isBorrowedAccountUnavailable(error)) {
                        endBorrowedPagination("renew_failed")
                    } else {
                        Observable.error(error)
                    }
                }
            }
        }
    }

    /**
     * 借号在翻页途中失效时的终止页。
     *
     * 这里**绝不能**回落到 [ceui.lisa.http.AppApi.getNextIllust]：nextUrl 是「会员专属 sort」的
     * 游标，而那个方法没有 explicit-authorization 标记，会被 Retro 的拦截器注入当前登录账号的
     * token —— 用非会员的自己的号去打会员游标必然 400，用户每点一次重试就再撞一次，形成死循环。
     *
     * 返回空的终止页（无 next_url）让列表停在已拿到的结果上；重新搜索会走 [initApi] 建新会话、
     * 借一个新号从头开始。
     */
    private fun endBorrowedPagination(reason: String): Observable<ListIllust> {
        Timber.tag(NANA7MI_LOG_TAG).w(
            "stage=official_search_next result=borrow_lost reason=%s action=end_pagination",
            reason,
        )
        // Mapper.apply 会遍历 getList()，null 会 NPE —— 必须显式给空列表。
        return Observable.just(ListIllust().apply { illusts = emptyList() })
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        if (this.filterMapper == null) {
            this.filterMapper = FilterMapper().enableFilterStarSize()
        }
        return this.filterMapper!!
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        // 已下线的「机内自带热度排序」在这里就归一掉（老配置里可能还存着），下游一路
        // 只会看到 pixiv 认识的值。见 [SortType.sanitize]。
        sortType = SortType.sanitize(searchModel.sortType.value)
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

    private companion object {
        const val NANA7MI_LOG_TAG = "sadadsdasdw2"
    }
}
