package ceui.loxia

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PixivWebApi {

    //

    @GET("/rpc/index.php?mode=latest_message_threads2&num=10&offset=0")
    suspend fun getMessageList()

    /**
     * issue #592: 作品详情。app-api 对部分作品返回 visible=false 的空壳时用它兜底,
     * SFW 无 cookie 也能拿,R18 detail 亦可匿名拉到。
     */
    @GET("/ajax/illust/{illust_id}")
    suspend fun getWebIllust(
        @Path("illust_id") illust_id: Long,
        @Query("lang") lang: String = "zh",
    ): WebResponse<WebIllustBody>

    /**
     * 每一 P 的真实原图宽高(app-api 的 meta_pages 只给 image_urls、不带宽高)。详情页多 P 用它在
     * 下载前就把后续页展示高度摆准,消除首帧「兜底高→自然高」的跳。需要网页 cookie;缺失时接口
     * 会 error/403,调用方静默降级到图片就绪后的异步定高。见 IllustAdapter.seedPageDimensions。
     */
    @GET("/ajax/illust/{illust_id}/pages?lang=zh")
    suspend fun getIllustPages(
        @Path("illust_id") illust_id: Long,
    ): WebResponse<List<WebIllustPage>>

    @GET("/ajax/tags/frequent/illust")
    suspend fun getFrequentTags(
        @Query("ids[]") ids: List<Long>,
        @Query("lang") lang: String = "zh",
    ): WebResponse<List<FrequentTag>>

    /**
     * 画师的**全量**作品标签(网页版画师页「高级搜索」面板的数据源)。
     *
     * 网页在进画师页时就一次性把它拉完,所以点开面板不再发任何请求 —— 这里同样一次拿全,
     * 无分页、无需 cookie(匿名可拿)。实测某画师返回 1933 条。
     *
     * [category] 取 `illusts` / `manga` / `novels`(issue #996,三者同构;另有 `illustmanga`
     * 插画+漫画合并变体,暂未用);`all=1` 比不带多出一小撮 tag(manga/novels 实测也认这个参数);
     * `lang` 决定 [UserWorkTag.tag_translation] 是否有值。
     */
    @GET("/ajax/user/{user_id}/{category}/tags")
    suspend fun getUserWorkTags(
        @Path("user_id") userId: Long,
        @Path("category") category: String,
        @Query("all") all: Int = 1,
        @Query("lang") lang: String = "zh",
    ): WebResponse<List<UserWorkTag>>

    /**
     * issue #1005: 某篇小说的「相关作品」推荐。app-api 没有 novel 版 related 端点
     *（illust 的 /v2/illust/related 无小说对应物），借网页版小说页同款数据源。
     * 这里只取推荐 id，完整数据由调用方用 app-api 的 novel/detail 补水——收藏态 /
     * 过滤口径才能与其它小说列表一致。SFW 匿名可拿；失败按「无相关」降级。
     */
    @GET("/ajax/novel/{novel_id}/recommend/init")
    suspend fun getNovelRecommendInit(
        @Path("novel_id") novelId: Long,
        @Query("limit") limit: Int,
        @Query("lang") lang: String = "zh",
    ): WebResponse<NovelRecommendInitBody>

    @GET("/ajax/user/{user_id}")
    suspend fun getWebUserDetail(
        @Path("user_id") userId: Long,
        @Query("full") full: Int = 1,
        @Query("lang") lang: String = "zh",
    ): WebResponse<WebUserDetail>

    @GET("/ajax/top/{type}?mode=all&lang=zh")
    suspend fun getSquareContents(
        @Path("type") type: String,
    ): SquareResponse


    @GET("/touch/ajax/user/bookmarks?p=1&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getBookmarkedIllust(
        @Query("id") id: Long,
        @Query("type") type: String,
        @Query("rest") rest: String,
    ): SquareResponse

    @GET("/touch/ajax/user/related?p=1&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getRelatedUsers(
        @Query("id") id: Long,
        @Query("type") type: String,
        @Query("rest") rest: String,
    ): SquareResponse

    @GET("/touch/ajax/recommender/top_items?mode=safe&lang=zh")
    suspend fun getMessageListBBBB()


    @GET("/touch/ajax/search/illusts?include_meta=1&type=all&csw=0&s_mode=s_tag_full&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getCircleDetail(
        @Query("word") word: String,
    ): CircleResponse

    /**
     * issue #1016: 网页版小说搜索的「シリーズ単位で表示」（前端叫 groupBySeries，落到 `gs=1`）。
     * 同系列的上百章塌成一条系列卡，app-api 的 `/v1/search/novel` 没有对应参数，只能走网页。
     *
     * 参数名与 app-api 那套完全不同（对照见 [ceui.pixiv.ui.search.SearchNovelSeriesWebSource]）：
     * `s_mode` 小说侧的 `s_tc` 是**正文**（不是插画的标题简介），`scd/ecd` 是投稿期间，
     * `blt` 是收藏数下限，`tlt/tgt`·`wlt/wgt`·`rlt/rgt` 分别是字数 / 单词数 / 阅读用时区间。
     *
     * 匿名（无网页 cookie）也能调通，但两处会降级：R-18 结果拿不到；`order=popular_d` 被服务端
     * 静默忽略（网页热门排序是会员专属，app 那条借号的路子在这里用不上——借来的是 app-api
     * 的 OAuth token，不是网页会员 cookie）。
     */
    @GET("/ajax/search/novels/{word}")
    suspend fun searchNovelsGroupedBySeries(
        @Path("word") word: String,
        @Query("word") wordQuery: String,
        @Query("p") page: Int,
        @Query("gs") groupBySeries: Int,
        @Query("order") order: String,
        @Query("mode") mode: String,
        @Query("s_mode") sMode: String,
        @Query("scd") startDate: String? = null,
        @Query("ecd") endDate: String? = null,
        @Query("blt") bookmarkMin: Int? = null,
        @Query("tlt") textLengthMin: Int? = null,
        @Query("tgt") textLengthMax: Int? = null,
        @Query("wlt") wordCountMin: Int? = null,
        @Query("wgt") wordCountMax: Int? = null,
        @Query("rlt") readingTimeMin: Int? = null,
        @Query("rgt") readingTimeMax: Int? = null,
        @Query("original_only") originalOnly: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("work_lang") workLang: String? = null,
        @Query("replaceable_only") replaceableOnly: Int? = null,
        @Query("ai_type") aiType: Int? = null,
        @Query("lang") lang: String = "zh",
    ): WebResponse<WebNovelSearchBody>

    @POST("/ajax/street/v2/main")
    suspend fun getStreetMain(
        @Header("x-csrf-token") csrfToken: String,
        @Body request: StreetRequest,
    ): StreetResponse

    /**
     * issue #959: 读某个画师当前的 pixiv 官方拉黑态。带 target_id 时返回的
     * block_items 里必含目标本人一条(isTarget=true),看它的 isBlocked 即可。
     * 需要网页 cookie。
     */
    @GET("/ajax/block/list")
    suspend fun getBlockList(
        @Query("target_id") targetId: Long,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 24,
        @Query("lang") lang: String = "zh",
    ): WebResponse<BlockListBody>

    /**
     * issue #959: 拉黑 / 取消拉黑某画师(pixiv 账号级,不是本地屏蔽)。
     * 需要网页 cookie + x-csrf-token。
     */
    @POST("/ajax/block/save")
    suspend fun saveBlock(
        @Header("x-csrf-token") csrfToken: String,
        @Body request: BlockSaveRequest,
    ): WebResponse<Any>

    /**
     * issue #1023: 作品标签的可编辑态 —— 谁加的、哪些能删、我能不能加。
     *
     * 官方 App 没有「编辑标签」,这是网页独有的社区标签机制,所以只能走网页这条。
     * 匿名也能 GET 到(拿来判断 writable 恒 false),真要写就得有网页 cookie。
     */
    @GET("/ajax/tags/illust/{illust_id}")
    suspend fun getIllustEditableTags(
        @Path("illust_id") illustId: Long,
        @Query("lang") lang: String = "zh",
    ): WebResponse<WorkTagsBody>

    /**
     * issue #1023: 给作品加一个标签。一次一个,加多个就多调几次。
     * 需要网页 cookie + x-csrf-token。
     */
    @POST("/ajax/tags/illust/{illust_id}/add")
    suspend fun addIllustTag(
        @Path("illust_id") illustId: Long,
        @Header("x-csrf-token") csrfToken: String,
        @Body request: WorkTagEditRequest,
    ): WebResponse<Any>

    /**
     * issue #1023: 删掉作品上的一个标签。只有 [WorkEditableTag.deletable] 为 true 的那些能删。
     * 需要网页 cookie + x-csrf-token。
     */
    @POST("/ajax/tags/illust/{illust_id}/delete")
    suspend fun deleteIllustTag(
        @Path("illust_id") illustId: Long,
        @Header("x-csrf-token") csrfToken: String,
        @Body request: WorkTagEditRequest,
    ): WebResponse<Any>
}