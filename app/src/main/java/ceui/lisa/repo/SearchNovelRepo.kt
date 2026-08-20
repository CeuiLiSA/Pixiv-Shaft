package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.core.Mapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
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

class SearchNovelRepo @JvmOverloads constructor(
    var keyword: String?,
    private var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    private var isPremium: Boolean?,
    private var startDate: String?,
    private var endDate: String?,
    private var r18Restriction: Int?,
    private var bookmarkMin: Int? = null,
    private var bookmarkMax: Int? = null,
    private var genre: Int? = null,
    private var lang: String? = null,
    private var searchAiType: Int? = null,
    private var isOriginalOnly: Boolean? = null,
    private var isReplaceableOnly: Boolean? = null,
    // 正文长度 / 阅读用时 6 项 —— V3 sheet 写入；mockup 参数名待真实抓包替换
    private var textLengthMin: Int? = null,
    private var textLengthMax: Int? = null,
    private var wordCountMin: Int? = null,
    private var wordCountMax: Int? = null,
    private var readingTimeMin: Int? = null,
    private var readingTimeMax: Int? = null,
    /**
     * 投稿期间相对预设档（[DurationBucket].name 字串形式）—— V3 sheet 写入。
     * 与 [startDate]/[endDate] 互斥：非 null 时 [initApi] 当场算 today−N 覆盖发出去，
     * 跨午夜也不会窗口停滞。null 时直接用 [startDate]/[endDate]（指定期间自定义）。
     */
    private var durationBucket: String? = null,
) : RemoteRepo<ListNovel>() {

    private var filterMapper: Mapper<ListNovel>? = null
    @Volatile
    private var nana7miSession = Nana7miAccountSession()
    @Volatile
    private var nana7miTelemetry: Nana7miSearchTelemetry.Flow? = null

    // 复用基类 Mapper（已含屏蔽 tag/ID/用户 + 全局 R18 过滤）；额外承载搜索「R-18 限制」三档。
    // 注意：mapper() 由 RemoteRepo 构造器调用，早于本类属性初始化，故这里不读 r18Restriction，
    // 实际档位在 update() 里推给 mapper（与 SearchIllustRepo 的 FilterMapper 同套路）。
    override fun mapper(): Function<in ListNovel, ListNovel> {
        if (filterMapper == null) {
            filterMapper = Mapper()
        }
        return filterMapper!!
    }

    override fun initApi(): Observable<ListNovel> {
        // A late completion from an older query must not overwrite the account for this query.
        val currentNana7miSession = Nana7miAccountSession()
        nana7miSession = currentNana7miSession
        nana7miTelemetry = null
        val useBookmarkQuery = (bookmarkMin ?: 0) > 0 || (bookmarkMax ?: 0) > 0
        val keywordSuffix = if (useBookmarkQuery) "" else when {
            TextUtils.isEmpty(starSize) -> ""
            else -> " $starSize"
        }
        // R18 三档不再拼 -R-18 / R-18 关键字（hack 匹配字面标签会让全年龄/R 混在一起）；
        // 改由 [mapper] 的 Mapper.setSearchR18Restriction 按真实 x_restrict 客户端过滤（见 update()）。
        val assembledKeyword: String = (keyword + keywordSuffix).trim()

        // popular_preview 是预览 endpoint 专属；非会员选择会员人气排序时借用一个 Nana7mi
        // 账号走正式搜索。取号/过期刷新/重新上报/400 重放由共享会话组件负责。
        // 借号搜索可以被服务端远程关掉（pixshaft-api /v1/config）。关掉后非会员的人气排序
        // 退回借号上线前的行为——直接走 popular-preview；绝不能落到 searchNovel，那是拿自己
        // 的非会员 token 打会员专属 sort，必然 400。
        val nana7miEnabled = RemoteAppConfig.nana7miSearchEnabled
        val wantsPremiumOnlySort =
            isPremium != true && sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE
        // 喜欢数筛选（bookmark_num_min/max）同样是会员专属参数——非会员设了就借号让服务端
        // 真过滤（与插画侧同一条规则）；显式「热度预览」除外（那档语义就是不花借号额度）。
        // 真会员两个 wants 都为 false，一律用自己的号直发。
        val wantsPremiumBookmarkFilter = isPremium != true && useBookmarkQuery
        val selectedPopularPreview = sortType == SortType.POPULAR_PREVIEW
        val usePopularPreview = selectedPopularPreview || (wantsPremiumOnlySort && !nana7miEnabled)
        // 仅喜欢数筛选（date 排序）在借号被远程关掉时不能落 preview（排序会被偷换成热度预览），
        // 落到下面 else 的普通直连
        val useBorrowedOfficial = nana7miEnabled && !selectedPopularPreview &&
                (wantsPremiumOnlySort || wantsPremiumBookmarkFilter)

        // 投稿期间相对档当场算 today−N(每次 initApi 都重算,跨午夜窗口自动跟随今天);
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        // 小说端点与插画不同：不传 search_target 时服务端按纯字面 keyword 匹配，
        // 标签同义词/译名不展开（#1038——搜「원신」搜不到 tag「原神」的小说；插画端点
        // 不传≡partial_match_for_tags，不受影响）。所以默认档「标签部分一致」这里
        // **显式**传 partial_match_for_tags（官方 app / PiPixiv 同此），同义词展开回来；
        // 首页 0 结果时再降级成不传重发一次，保住 #906 的纯标题命中（「淫神空间」
        // 「命运的花道」这类关键字只在标题里的作品）。两个档位 curl A/B 实测互斥，
        // 客户端只能这样两段式兼得。
        val explicitSearchTarget = SearchTarget.toQueryValue(searchType)
        val defaultTier = explicitSearchTarget == null
        val effectiveSearchTarget = explicitSearchTarget
            ?: SearchTarget.PartialMatchForTags.apiValue

        // 默认档首页空结果 → 去掉 search_target 按 keyword 语义重发；显式档位不降级
        //（用户点名要严格匹配语义，空了就是空了）。降级页的 next_url 会被 RemoteRepo
        // 存走，翻页自然跟着 keyword 游标走，不会两种语义混页。
        fun withTitleFallback(request: (String?) -> Observable<ListNovel>): Observable<ListNovel> {
            val first = request(effectiveSearchTarget)
            if (!defaultTier) return first
            return first.flatMap { response ->
                if (response.novels.isNullOrEmpty()) request(null) else Observable.just(response)
            }
        }
        val requesterUid = SessionManager.loggedInUid
        val telemetry = if (BuildConfig.IS_LITE) null else when {
            usePopularPreview -> Nana7miSearchTelemetry.start(
                requesterUid = requesterUid,
                contentType = Nana7miSearchTelemetry.ContentType.NOVEL,
                query = assembledKeyword,
                initialRoute = Nana7miSearchTelemetry.Route.PREVIEW_DIRECT,
                initialReason = if (selectedPopularPreview) "selected_preview" else "remote_disabled",
            )
            useBorrowedOfficial -> Nana7miSearchTelemetry.start(
                requesterUid = requesterUid,
                contentType = Nana7miSearchTelemetry.ContentType.NOVEL,
                query = assembledKeyword,
                initialRoute = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
            )
            else -> null
        }
        nana7miTelemetry = telemetry

        fun fallbackPreview(reason: String): Observable<ListNovel> {
            telemetry?.fallback(reason)
            Timber.tag(NANA7MI_LOG_TAG).w(
                "stage=route target=novel_popular_preview reason=%s",
                reason,
            )
            val source = withTitleFallback { target ->
                Retro.getAppApi().popularNovelPreview(
                    assembledKeyword,
                    effectiveStartDate,
                    effectiveEndDate,
                    target,
                    bookmarkMin,
                    bookmarkMax,
                    genre,
                    lang,
                    searchAiType,
                    isOriginalOnly,
                    isReplaceableOnly,
                    textLengthMin,
                    textLengthMax,
                    wordCountMin,
                    wordCountMax,
                    readingTimeMin,
                    readingTimeMax,
                )
            }
                .doOnNext { response ->
                    Timber.tag(NANA7MI_LOG_TAG).d(
                        "stage=novel_popular_preview result=success novel_count=%d has_next=%s",
                        response.novels?.size ?: 0,
                        !response.nextUrl.isNullOrBlank(),
                    )
                }
                .doOnError { error ->
                    Timber.tag(NANA7MI_LOG_TAG).w(
                        error,
                        "stage=novel_popular_preview result=failure error_type=%s",
                        error.javaClass.simpleName,
                    )
                }
            return telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        }

        // 仅喜欢数筛选（date 排序）的借号失败回退：排序保真走普通官方搜索（sort 此时必为
        // date_desc/date_asc，非会员发也合法），bookmark 参数被服务端忽略后无碍结果正确性。
        fun fallbackPlain(reason: String): Observable<ListNovel> {
            telemetry?.fallback(reason)
            Timber.tag(NANA7MI_LOG_TAG).w(
                "stage=route target=novel_plain_search reason=%s",
                reason,
            )
            val source = withTitleFallback { target ->
                Retro.getAppApi().searchNovel(
                    assembledKeyword,
                    sortType,
                    effectiveStartDate,
                    effectiveEndDate,
                    target,
                    bookmarkMin,
                    bookmarkMax,
                    genre,
                    lang,
                    searchAiType,
                    isOriginalOnly,
                    isReplaceableOnly,
                    textLengthMin,
                    textLengthMax,
                    wordCountMin,
                    wordCountMax,
                    readingTimeMin,
                    readingTimeMax,
                )
            }
            return telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        }

        // 借号失败按诉求分流：热度类诉求回退热度预览；仅喜欢数筛选回退普通直连
        fun fallbackAfterBorrowFailure(reason: String): Observable<ListNovel> =
            if (wantsPremiumOnlySort) fallbackPreview(reason) else fallbackPlain(reason)

        val result = if (usePopularPreview) {
            val source = withTitleFallback { target ->
                Retro.getAppApi().popularNovelPreview(
                    assembledKeyword,
                    effectiveStartDate,
                    effectiveEndDate,
                    target,
                    bookmarkMin,
                    bookmarkMax,
                    genre,
                    lang,
                    searchAiType,
                    isOriginalOnly,
                    isReplaceableOnly,
                    textLengthMin,
                    textLengthMax,
                    wordCountMin,
                    wordCountMax,
                    readingTimeMin,
                    readingTimeMax,
                )
            }
            telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.FIRST,
            ) ?: source
        } else if (useBorrowedOfficial) {
            Nana7miSearchSerial.run("novel_first") { lease ->
                Timber.tag(NANA7MI_LOG_TAG).d(
                    "stage=novel_flow event=start requester_uid=%d sort=%s keyword_length=%d",
                    requesterUid,
                    sortType,
                    assembledKeyword.length,
                )
                lease.blockingObservable {
                    runBlocking { currentNana7miSession.fetchReady() }
                }.flatMap { result ->
                    val borrowed = currentNana7miSession.payload
                    if (borrowed != null && !borrowed.expired) {
                        telemetry?.borrowed(borrowed.uid)
                        Timber.tag(NANA7MI_LOG_TAG).d(
                            "stage=route target=novel_official_search account_uid=%d sort=%s",
                            borrowed.uid,
                            sortType,
                        )
                        val source = currentNana7miSession.requestWithRefresh(
                            initial = borrowed,
                            stage = "novel_official_search",
                            lease = lease,
                            successDetails = { response ->
                                "novel_count=${response.novels?.size ?: 0} " +
                                        "has_next=${!response.nextUrl.isNullOrBlank()}"
                            },
                        ) { authorization ->
                            // 降级重发复用同一份 authorization，不再走一次借号/续期。
                            withTitleFallback { target ->
                                Retro.getAppApi().searchNovelWithAuth(
                                    authorization,
                                    assembledKeyword,
                                    sortType,
                                    effectiveStartDate,
                                    effectiveEndDate,
                                    target,
                                    bookmarkMin,
                                    bookmarkMax,
                                    genre,
                                    lang,
                                    searchAiType,
                                    isOriginalOnly,
                                    isReplaceableOnly,
                                    textLengthMin,
                                    textLengthMax,
                                    wordCountMin,
                                    wordCountMax,
                                    readingTimeMin,
                                    readingTimeMax,
                                )
                            }
                        }
                        (telemetry?.track(
                            source = source,
                            page = Nana7miSearchTelemetry.Page.FIRST,
                            route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                            borrowedUid = borrowed.uid,
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
        } else {
            withTitleFallback { target ->
                Retro.getAppApi().searchNovel(
                    assembledKeyword,
                    sortType,
                    effectiveStartDate,
                    effectiveEndDate,
                    target,
                    bookmarkMin,
                    bookmarkMax,
                    genre,
                    lang,
                    searchAiType,
                    isOriginalOnly,
                    isReplaceableOnly,
                    textLengthMin,
                    textLengthMax,
                    wordCountMin,
                    wordCountMax,
                    readingTimeMin,
                    readingTimeMax,
                )
            }
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

    override fun initNextApi(): Observable<ListNovel> {
        val session = nana7miSession
        val telemetry = nana7miTelemetry
        val borrowed = session.payload
        // Capture the cursor together with the session before this request waits for the
        // process-wide permit; a newer first page may otherwise replace RemoteRepo.nextUrl.
        val nextPageUrl = nextUrl
        return if (session.borrowedAccountLost) {
            endBorrowedPagination("already_lost")
        } else if (borrowed == null) {
            val source = Retro.getAppApi().getNextNovel(nextPageUrl)
            telemetry?.track(
                source = source,
                page = Nana7miSearchTelemetry.Page.NEXT,
            ) ?: source
        } else {
            Timber.tag(NANA7MI_LOG_TAG).d(
                "stage=novel_official_search_next event=request account_uid=%d",
                borrowed.uid,
            )
            Nana7miSearchSerial.run("novel_next") { lease ->
                val source = session.requestWithRefresh(
                    initial = borrowed,
                    stage = "novel_official_search_next",
                    lease = lease,
                    successDetails = { response ->
                        "novel_count=${response.novels?.size ?: 0} " +
                                "has_next=${!response.nextUrl.isNullOrBlank()}"
                    },
                ) { authorization ->
                    Retro.getAppApi().getNextNovelWithAuth(authorization, nextPageUrl)
                }
                (telemetry?.track(
                    source = source,
                    page = Nana7miSearchTelemetry.Page.NEXT,
                    route = Nana7miSearchTelemetry.Route.BORROWED_OFFICIAL,
                    borrowedUid = borrowed.uid,
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
     * 这里**绝不能**回落到 [ceui.lisa.http.AppApi.getNextNovel]：nextUrl 是「会员专属 sort」的
     * 游标，而那个方法没有 explicit-authorization 标记，会被 Retro 的拦截器注入当前登录账号的
     * token —— 用非会员的自己的号去打会员游标必然 400，用户每点一次重试就再撞一次，形成死循环。
     *
     * 返回空的终止页（无 next_url）让列表停在已拿到的结果上；重新搜索会走 [initApi] 建新会话、
     * 借一个新号从头开始。
     */
    private fun endBorrowedPagination(reason: String): Observable<ListNovel> {
        Timber.tag(NANA7MI_LOG_TAG).w(
            "stage=novel_official_search_next result=borrow_lost reason=%s action=end_pagination",
            reason,
        )
        // Mapper.apply 会遍历 getList()，null 会 NPE —— 必须显式给空列表。
        return Observable.just(ListNovel().apply { novels = emptyList() })
    }

    fun update(searchModel: SearchModel, keywordSnapshot: String? = searchModel.keyword.value) {
        // 与政策门控共用本代 keyword 快照，避免检查和实际 Retrofit 参数之间产生时序窗口。
        keyword = keywordSnapshot
        // 已下线的「机内自带热度排序」在这里就归一掉（老配置里可能还存着），下游一路
        // 只会看到 pixiv 认识的值——原样发出去是 400 Invalid value，而且 400 不是 OAuth
        // 错误，[isBorrowedAccountUnavailable] 也不成立，会既不回落 preview 又白借一个号。
        // 再过 novelSafe：SearchModel.sortType 与插画共享，插画侧的男/女性向人气（novel
        // 端点不识别）归一到总热度。
        sortType = SortType.novelSafe(SortType.sanitize(searchModel.sortType.value))
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        // 会员身份在发请求这一刻看 SessionManager（user/detail 的 profile.is_premium 静默同步
        // 维护的那份），不吃搜索页打开时的快照：真会员一律用自己的号打官方 sort，绝不借号；
        // 快照会让「进页后才同步到的会员」白借一次、「进页后才过期的会员」打 400。
        isPremium = SessionManager.isPremium
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value
        r18Restriction = searchModel.r18Restriction.value
        bookmarkMin = searchModel.bookmarkMin.value
        bookmarkMax = searchModel.bookmarkMax.value
        genre = searchModel.genre.value
        lang = searchModel.lang.value
        // AI：屏蔽走全局 isDeleteAIIllust → search_ai_type=1；「仅看 AI」会话态（issue #909）→
        // 服务端全返(0)，再由 Mapper 客户端按 novel_ai_type==2 筛。
        val onlyAi = searchModel.onlyAi.value == true
        searchAiType = if (onlyAi) 0 else if (Shaft.sSettings.isDeleteAIIllust) 1 else 0
        // null 让 retrofit 跳过 query；只有显式 true 才传，行为对齐 iOS（关闭时不带）
        isOriginalOnly = if (searchModel.isOriginalOnly.value == true) true else null
        isReplaceableOnly = if (searchModel.isReplaceableOnly.value == true) true else null
        textLengthMin = searchModel.textLengthMin.value
        textLengthMax = searchModel.textLengthMax.value
        wordCountMin = searchModel.wordCountMin.value
        wordCountMax = searchModel.wordCountMax.value
        readingTimeMin = searchModel.readingTimeMin.value
        readingTimeMax = searchModel.readingTimeMax.value
        durationBucket = searchModel.durationBucket.value
        // R18 三档（0=不限/1=仅安全/2=仅R-18）→ 客户端按 x_restrict 过滤
        filterMapper?.setSearchR18Restriction(r18Restriction ?: 0)
        filterMapper?.setSearchOnlyAi(onlyAi)
    }

    private companion object {
        const val NANA7MI_LOG_TAG = "sadadsdasdw2"
    }
}
