package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.loxia.Nana7miPayload
import ceui.pixiv.actions.Nana7miSearchTelemetry
import ceui.pixiv.config.RemoteAppConfig
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
    private var bookmarkMax: Int? = null,
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
    // 进程级服务，由构造方（Fragment）从 ServicesProvider 取出注入；Repo 自己不认识 Application。
    private val nana7miOutbox: AccountOnlineReportOutbox,
    private val nana7miTelemetryService: Nana7miSearchTelemetry,
    private val remoteAppConfig: RemoteAppConfig,
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    // Repo 实例级借用会话，不切换应用登录态；插画和小说共用同一套刷新/上报规则。
    @Volatile
    private var nana7miSession = Nana7miAccountSession(nana7miOutbox)

    @Volatile
    private var nana7miTelemetry: Nana7miSearchTelemetry.Flow? = null

    /**
     * 首屏来自 pixshaft 缓存：手里的 next_url 是**会员专属游标**，但这轮还没借过号。翻页时
     * 缓存未命中要现借，绝不能落到 [initNextApi] 里「payload == null → 用自己的号直连」那支。
     */
    @Volatile
    private var borrowedCursorFromCache = false

    override fun initApi(): Observable<ListIllust> {
        // 每轮首屏使用全新会话。即使上一轮请求取消得较晚，它也只能更新旧会话，不能把
        // 旧借用账号重新写进当前查询，污染当前结果的 next_url 翻页。
        val currentNana7miSession = Nana7miAccountSession(nana7miOutbox)
        nana7miSession = currentNana7miSession
        nana7miTelemetry = null
        borrowedCursorFromCache = false
        // 关键字写搜索历史已上移到 SearchActivity（首搜 initModel + 重搜 nowGo，按 id 去重收口）。
        // 不再寄生在这里——原来只有插画 tab 触发，小说/作者 tab 漏写。

        // 收藏量两条桶并存：
        //  - bookmarkMin/Max 走官方 `bookmark_num_min/max` query 参数（仅会员 popular 生效）
        //  - starSize（"Xusers入り"）作为关键字后缀拼到 query 里（对非会员有效，命中 pixiv
        //    自动桶标签）
        // 两者来自 V3 sheet 的两个独立维度（bookmarkRange / keywordUsersBucket），用户可同时设置。
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
        val wantsPremiumOnlySort = isPremium != true && (
                sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                        sortType == SortType.POPULAR_MALE_DESC ||
                        sortType == SortType.POPULAR_FEMALE_DESC
                )
        // 喜欢数筛选（bookmark_num_min/max）同样是会员专属参数——非会员拿自己的号发，
        // 服务端静默忽略，只剩 FilterMapper 客户端兜底。设了就借号，让服务端真过滤；
        // 显式选「热度预览」的除外（那档语义就是不花借号额度，bookmark 继续客户端兜底）。
        // 真会员（isPremium==true）两个 wants 都为 false，一律用自己的号直发，绝不借号。
        val wantsPremiumBookmarkFilter = isPremium != true &&
                ((bookmarkMin ?: 0) > 0 || (bookmarkMax ?: 0) > 0)
        // 借号搜索可以被服务端远程关掉（pixshaft-api /v1/config）。关掉后非会员的人气排序
        // 退回借号上线前的行为——直接走 popular-preview。绝不能落到下面的 searchIllust：
        // 那是拿自己的非会员 token 打会员专属 sort，必然 400。
        val nana7miEnabled = remoteAppConfig.nana7miSearchEnabled
        val selectedPopularPreview = sortType == SortType.POPULAR_PREVIEW
        val usePopularPreview = selectedPopularPreview || (wantsPremiumOnlySort && !nana7miEnabled)
        // 仅喜欢数筛选（date 排序）在借号被远程关掉时**不能**落 preview——那会把排序偷换成
        // 热度预览；落到下面 else 的普通直连，排序保真、bookmark 靠客户端兜底。
        val useBorrowedOfficial = nana7miEnabled && !selectedPopularPreview &&
                (wantsPremiumOnlySort || wantsPremiumBookmarkFilter)

        // 投稿期间相对档当场算 today−N（每次 initApi 都重算,跨午夜窗口自动跟随今天）;
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        // 默认档「标签部分一致」不传 search_target，让标题命中也能搜到（#906）——
        // 见 [SearchTarget.toQueryValue] 注释。
        val effectiveSearchTarget = SearchTarget.toQueryValue(searchType)
        val requesterUid = SessionManager.loggedInUid
        val telemetry = if (BuildConfig.IS_LITE) null else when {
            usePopularPreview -> nana7miTelemetryService.beginFlow(
                requesterUid = requesterUid,
                contentType = Nana7miSearchTelemetry.ContentType.ILLUST,
                query = assembledKeyword,
                initialRoute = Nana7miSearchTelemetry.Route.PREVIEW_DIRECT,
                initialReason = if (selectedPopularPreview) "selected_preview" else "remote_disabled",
            )
            useBorrowedOfficial -> nana7miTelemetryService.beginFlow(
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
                bookmarkMax,
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

        // 仅喜欢数筛选（date 排序）的借号失败回退：排序必须保真，不能换成热度预览——
        // 用自己的号走普通官方搜索（sort 此时必为 date_desc/date_asc，非会员发也合法），
        // bookmark 参数被服务端忽略后由 FilterMapper 客户端兜底。
        fun fallbackPlain(reason: String): Observable<ListIllust> {
            telemetry?.fallback(reason)
            Timber.tag(NANA7MI_LOG_TAG).w(
                "stage=route target=plain_search reason=%s",
                reason,
            )
            val source = Retro.getAppApi().searchIllust(
                assembledKeyword,
                sortType,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                bookmarkMax,
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
            return telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        }

        // 借号失败按诉求分流：热度类诉求回退热度预览（排序语义最近）；仅喜欢数筛选回退普通直连
        fun fallbackAfterBorrowFailure(reason: String): Observable<ListIllust> =
            if (wantsPremiumOnlySort) fallbackPreview(reason) else fallbackPlain(reason)

        val result = if (usePopularPreview) {
            val source = Retro.getAppApi().popularPreview(
                assembledKeyword,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                bookmarkMax,
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
        } else if (useBorrowedOfficial) {
            // 借号之前先问 pixshaft：同样的请求别人（或自己）刚搜过就直接拿那页，不派发、
            // 不 renew、不打 Pixiv。key 必须覆盖下面 searchIllustWithAuth 发出去的每一个参数。
            val cacheKind = Nana7miSearchCache.Kind.ILLUST
            val cacheKey = Nana7miSearchCache.firstPageKey(
                cacheKind,
                listOf(
                    "word" to assembledKeyword,
                    "sort" to sortType,
                    "start_date" to effectiveStartDate,
                    "end_date" to effectiveEndDate,
                    "search_target" to effectiveSearchTarget,
                    "bookmark_num_min" to bookmarkMin,
                    "bookmark_num_max" to bookmarkMax,
                    "tool" to tool,
                    "lang" to lang,
                    "search_ai_type" to searchAiType,
                    "ratio" to ratioPattern,
                    "content_type" to contentType,
                    "width_min" to widthMin,
                    "width_max" to widthMax,
                    "height_min" to heightMin,
                    "height_max" to heightMax,
                ),
            )
            val borrowedFlow = Nana7miSearchSerial.run("illust_first") { lease ->
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
                                bookmarkMax,
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
                        }.doOnNext { page ->
                            // 只回填真正借号打到的官方结果；回退页（preview / 直连）不是同一个东西。
                            Nana7miSearchCache.store(cacheKind, cacheKey, page, "official_search")
                        }
                        (telemetry?.track(
                            source = source,
                            page = Nana7miSearchTelemetry.Page.FIRST,
                            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                            borrowedUid = newNana7mi.uid,
                        ) ?: source).onErrorResumeNext { error: Throwable ->
                            if (isBorrowedAccountUnavailable(error)) {
                                fallbackAfterBorrowFailure("borrowed_refresh_failed")
                            } else {
                                Observable.error(error)
                            }
                        }
                    } else {
                        fallbackAfterBorrowFailure(currentNana7miSession.resultLabel(result))
                    }
                }
            }
            // 命中时整条借号 flow 不订阅：没有 flow_started / request 事件，遥测里就不会出现一个
            // 没借过号的「official_success」；这轮后续翻页也不再上报（nana7miTelemetry 置空）。
            Nana7miSearchCache.firstOrElse(
                kind = cacheKind,
                key = cacheKey,
                maxAgeMs = Nana7miSearchCache.maxAgeMsFor(sortType),
                type = ListIllust::class.java,
                stage = "official_search",
                onHit = {
                    borrowedCursorFromCache = true
                    nana7miTelemetry = null
                },
            ) { telemetry?.observeFirst(borrowedFlow) ?: borrowedFlow }
        } else {
            Retro.getAppApi().searchIllust(
                assembledKeyword,
                sortType,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                bookmarkMax,
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
        // 借号分支已经在缓存未命中那一侧自己包了 observeFirst（命中时不能包）。
        return if (useBorrowedOfficial) result else telemetry?.observeFirst(result) ?: result
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
        val cursorFromCache = borrowedCursorFromCache
        // nextUrl 与借用会话必须来自同一轮翻页。串行队列可能让真正订阅延后；若此时
        // 新首屏改写了 RemoteRepo.nextUrl，闭包里再读字段会拼出“旧账号 + 新游标”。
        val nextPageUrl = nextUrl
        return if (session.borrowedAccountLost) {
            endBorrowedPagination("already_lost")
        } else if (payload == null && !cursorFromCache) {
            val source = Retro.getAppApi().getNextIllust(nextPageUrl)
            telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.NEXT,
            ) ?: source
        } else {
            // 会员专属游标：先问缓存（别人翻过的页直接拿），未命中再用借来的号打；首屏来自缓存
            // 的话手里还没有号，这时才借。
            val cacheKind = Nana7miSearchCache.Kind.ILLUST
            val cacheKey = Nana7miSearchCache.nextPageKey(cacheKind, nextPageUrl)
            Nana7miSearchCache.firstOrElse(
                kind = cacheKind,
                key = cacheKey,
                maxAgeMs = Nana7miSearchCache.maxAgeMsFor(sortType),
                type = ListIllust::class.java,
                stage = "official_search_next",
            ) {
                Nana7miSearchSerial.run("illust_next") { lease ->
                    val ready: Observable<Nana7miPayload> = if (payload != null) {
                        Observable.just(payload)
                    } else {
                        Timber.tag(NANA7MI_LOG_TAG).d(
                            "stage=official_search_next event=borrow_for_cached_cursor",
                        )
                        lease.blockingObservable {
                            runBlocking { session.fetchReady() }
                            session.payload?.takeIf { !it.expired }
                                ?: throw BorrowedAccountUnavailableException(
                                    IllegalStateException("no borrowed account for cached cursor"),
                                )
                        }
                    }
                    ready.flatMap { current ->
                        Timber.tag(NANA7MI_LOG_TAG).d(
                            "stage=official_search_next event=request account_uid=%d",
                            current.uid,
                        )
                        val source = session.requestWithRefresh(
                            initial = current,
                            stage = "official_search_next",
                            lease = lease,
                            successDetails = { response ->
                                "illust_count=${response.illusts?.size ?: 0} " +
                                        "has_next=${!response.next_url.isNullOrBlank()}"
                            },
                        ) { authorization ->
                            Retro.getAppApi().getNextIllustWithAuth(authorization, nextPageUrl)
                        }.doOnNext { page ->
                            Nana7miSearchCache.store(cacheKind, cacheKey, page, "official_search_next")
                        }
                        telemetry?.track(
                            source = source,
                            page = Nana7miSearchTelemetry.Page.NEXT,
                            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                            borrowedUid = current.uid,
                        ) ?: source
                    }.onErrorResumeNext { error: Throwable ->
                        if (isBorrowedAccountUnavailable(error)) {
                            endBorrowedPagination("renew_failed")
                        } else {
                            Observable.error(error)
                        }
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

    fun update(searchModel: SearchModel, keywordSnapshot: String? = searchModel.keyword.value) {
        // 搜索页在策略判断前已固定本代 keyword；这里必须复用同一快照，不能在后台切线程后
        // 再从会随输入变化的 LiveData 取一次，否则会出现“检查的是旧词、请求发的是新词”。
        keyword = keywordSnapshot
        // 已下线的「机内自带热度排序」在这里就归一掉（老配置里可能还存着），下游一路
        // 只会看到 pixiv 认识的值。见 [SortType.sanitize]。
        sortType = SortType.sanitize(searchModel.sortType.value)
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        //isPopular = pop
        // 会员身份在发请求这一刻看 SessionManager（user/detail 的 profile.is_premium 静默同步
        // 维护的那份），不吃搜索页打开时的快照：真会员一律用自己的号打官方 sort，绝不借号；
        // 快照会让「进页后才同步到的会员」白借一次、「进页后才过期的会员」打 400。
        isPremium = SessionManager.isPremium
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value
        r18Restriction = searchModel.r18Restriction.value
        bookmarkMin = searchModel.bookmarkMin.value
        bookmarkMax = searchModel.bookmarkMax.value
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
        // 模糊粒子化或存在豁免作者时服务端不能直接剔除 AI，必须全量返回后由客户端滤/遮。
        val clientSideAi = onlyAi || (Shaft.sSettings.isDeleteAIIllust && Shaft.sSettings.isAiBlockClientSide)
        searchAiType = if (clientSideAi) 0 else if (Shaft.sSettings.isDeleteAIIllust) 1 else 0

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
        // 区间上限只有官方 query 一条来源（starSize 关键字桶没有上限语义）；
        // popular-preview 忽略 bookmark 参数，客户端兜底让区间在非会员路径上也成立
        this.filterMapper?.updateStarSizeMaxLimit(bookmarkMax ?: 0)
        // R18 三档（0=不限/1=仅安全/2=仅R-18）→ 客户端按 x_restrict 过滤
        this.filterMapper?.setSearchR18Restriction(r18Restriction ?: 0)
        this.filterMapper?.setSearchOnlyAi(onlyAi)
        this.filterMapper?.setKeepAiForBlur(true)
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
