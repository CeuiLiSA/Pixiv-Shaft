package ceui.pixiv.ui.search

import ceui.lisa.activities.Shaft
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Client
import ceui.loxia.ImageUrls
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.loxia.WebNovelCollection
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.ui.search.v3.R18Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * issue #1016：搜索小说的「系列作品归纳」数据源。
 *
 * app-api 的 `/v1/search/novel` 没有归纳能力——同一部连载的上百章会一条条铺满搜索结果，正是
 * 报告人抱怨的「被上百章的连载长篇卡很长的界面」。官方只有网页端做了这件事（前端叫
 * groupBySeries，落到 `gs=1`），所以归纳模式**整条列表改走 www.pixiv.net 的 ajax**，与关着
 * 开关时的 app-api 路径并存（分叉点在 [SearchNovelFeedSource.load]）。
 *
 * 换 host 意味着换了一套鉴权身份，两处必然降级，UI 侧已在开关副标题里写明：
 *   - R-18 结果要网页 cookie（[ceui.pixiv.session.SessionManager.hasWebCookie]），匿名视角看不到；
 *   - `order=popular_d` 对非会员被服务端静默忽略（实测与 date_d 返回完全一致）。app-api 那条
 *     借号跑热门的路子在这里用不上：借来的是 OAuth token，不是网页会员 cookie。
 *
 * 过滤也必须自己补齐：app-api 路径的屏蔽 tag / 屏蔽画师 / R-18 / 反刷屏是 [ceui.lisa.core.Mapper]
 * 对着 Novel 做的，网页返回的是另一套对象，一条都不会命中。这里把两种条目都先映射成 loxia
 * [Novel] 再走 [NovelFeedItem.of]（与全 app 同一条过滤链），搜索专属的 R-18 三档 / 仅看 AI 在
 * 本文件内补。
 */
class SearchNovelSeriesWebSource(private val searchModel: SearchModel) : FeedSource<String> {

    private var generationParams: WebNovelSearchParams? = null

    /** 新一代首页显式接收已经通过策略检查的 keyword 快照。 */
    suspend fun loadFirstPage(keywordSnapshot: String): FeedPage<String> {
        val params = buildWebNovelSearchParams(
            searchModel = searchModel,
            keywordSnapshot = keywordSnapshot,
        ).also { generationParams = it }
        return loadPage(page = 1, params = params)
    }

    override suspend fun load(cursor: String?): FeedPage<String> {
        val page = cursor?.toIntOrNull() ?: 1
        val params = if (cursor == null) {
            buildWebNovelSearchParams(searchModel).also { generationParams = it }
        } else {
            generationParams ?: buildWebNovelSearchParams(searchModel).also { generationParams = it }
        }
        return loadPage(page = page, params = params)
    }

    private suspend fun loadPage(page: Int, params: WebNovelSearchParams): FeedPage<String> {
        val resp = Client.webApi.searchNovelsGroupedBySeries(
            word = params.keyword,
            wordQuery = params.keyword,
            page = page,
            groupBySeries = 1,
            order = params.order,
            mode = params.mode,
            sMode = params.sMode,
            startDate = params.startDate,
            endDate = params.endDate,
            bookmarkMin = params.bookmarkMin,
            bookmarkMax = params.bookmarkMax,
            textLengthMin = params.textLengthMin,
            textLengthMax = params.textLengthMax,
            wordCountMin = params.wordCountMin,
            wordCountMax = params.wordCountMax,
            readingTimeMin = params.readingTimeMin,
            readingTimeMax = params.readingTimeMax,
            originalOnly = params.originalOnly,
            genre = params.genre,
            workLang = params.workLang,
            replaceableOnly = params.replaceableOnly,
            aiType = params.aiType,
        )
        // 网页 ajax 的业务错误是 HTTP 200 + error:true + body:null（同 UserNovelByTagFeedSource）：
        // 抛出去交给 feeds 的错误态，别渲染成「搜出来 0 件」。
        if (resp.error == true) {
            throw RuntimeException(resp.message.orEmpty().ifEmpty { "search/novels gs=1 failed" })
        }
        val section = resp.body?.novel
        val rows = section?.data.orEmpty()
        val items = withContext(Dispatchers.Default) {
            rows.mapNotNull { toFeedItem(it, params) }
        }
        // lastPage 是服务端给的翻页上限（匿名视角封顶 10 页）；空页也当到底，别继续空转。
        val hasNext = rows.isNotEmpty() && page < (section?.lastPage ?: 0)
        return FeedPage(items, if (hasNext) (page + 1).toString() else null)
    }

    /**
     * 一条结果 → feed 条目。系列条目和单篇条目**都**先落成 loxia [Novel] 再过
     * [NovelFeedItem.of]，让两种卡共用同一条内容过滤链；系列那条随后换壳成
     * [SearchNovelSeriesFeedItem]（卡片形态不同，身份也不能和小说 id 混在一起）。
     */
    private fun toFeedItem(row: WebNovelCollection, params: WebNovelSearchParams): FeedItem? {
        // 搜索专属两档（app-api 路径由 Mapper.setSearchR18Restriction / setSearchOnlyAi 承担）
        if (!params.r18Mode.accepts(row.xRestrict)) return null
        if (params.onlyAi && row.aiType != 2) return null

        val novelId = row.novelId?.toLongOrNull()
        if (novelId != null) {
            // 单篇：id 字段是 pixiv 给单篇造的 collection id，真正的小说 id 在 novelId 上。
            return NovelFeedItem.of(row.toNovel(novelId))
        }
        val seriesId = row.id?.toLongOrNull() ?: return null
        val representative = row.toNovel(seriesId, asSeries = true)
        // 过滤链只读 tags / user / text_length，对系列这份代表数据同样成立；过不了就整条不出现。
        // 全局 R-18 过滤照常挂着（不因搜索选了「仅 R-18」就让步），与 [ceui.lisa.core.Mapper] 一致。
        // 注意反刷屏（issue #743）看的是**整个系列的已公开字数**，不是单章：设了「最长字数」的
        // 用户会连带滤掉长连载——这与那条设置「不想看长文」的本意一致，故不特判。
        if (NovelFeedItem.of(representative) == null) return null
        return SearchNovelSeriesFeedItem(
            seriesId = seriesId,
            novel = representative,
            episodeCount = row.publishedEpisodeCount.takeIf { it > 0 } ?: row.episodeCount,
            isConcluded = row.isConcluded,
        )
    }
}

/**
 * 归纳模式下的「系列」卡条目。复用主力小说卡布局（`recy_novel`）渲染，所以内容也装在一个
 * loxia [Novel] 里；[seriesId] 单独拎出来做身份 + 点击跳转，**不能**用 novel.id 当小说 id——
 * 它是系列 id，喂给小说详情接口会 404。
 */
data class SearchNovelSeriesFeedItem(
    val seriesId: Long,
    val novel: Novel,
    val episodeCount: Int,
    val isConcluded: Boolean,
) : FeedItem {

    override val feedKey: Any get() = seriesId
}

/**
 * 网页搜索条目 → loxia [Novel]。封面是 novel-cover-master 的多尺寸图，与 app-api 同一 CDN，
 * 直接三档同填；tags 只有字符串名，无译名；visible 置 true 免得被当不可见滤掉。
 *
 * [asSeries] = 系列条目：字数/时间取「已公开」那份（未公开的付费话不算），日期取最新一话的
 * 投稿时间，并把 series 字段填成自己——卡片上那行「系列」文本由渲染方另出，这里填上是为了
 * 让过滤链和别处的 Novel 长得一样。
 */
internal fun WebNovelCollection.toNovel(id: Long, asSeries: Boolean = false): Novel {
    val cover = cover?.urls?.let { it.width480 ?: it.width240 ?: it.original }
    return Novel(
        id = id,
        title = title ?: "",
        caption = caption,
        create_date = (if (asSeries) latestPublishDateTime else publishedDateTime) ?: createDateTime,
        image_urls = cover?.let { ImageUrls(large = it, medium = it, square_medium = it) },
        tags = tags?.map { Tag(name = it) } ?: emptyList(),
        series = if (asSeries) Series(id = id, title = title) else null,
        text_length = publishedTextLength.takeIf { it > 0 } ?: textLength,
        total_bookmarks = bookmarkCount,
        // 已同步网页 cookie 时 bookmarkData 非 null = 已收藏；匿名视角恒 null，与网页侧其它
        // 精简数据同属已知局限，点进详情会按 id 拉全量。
        is_bookmarked = bookmarkData != null,
        user = User(
            id = userId,
            name = userName ?: "",
            profile_image_urls = profileImageUrl?.takeIf { it.isNotEmpty() }
                ?.let { ImageUrls(medium = it) },
        ),
        visible = true,
        x_restrict = xRestrict,
        novel_ai_type = aiType,
    )
}

/** 一次网页搜索请求的全部 query（+ 两个只在客户端生效的档位）。 */
internal data class WebNovelSearchParams(
    val keyword: String,
    val order: String,
    val mode: String,
    val sMode: String,
    val startDate: String?,
    val endDate: String?,
    val bookmarkMin: Int?,
    val bookmarkMax: Int?,
    val textLengthMin: Int?,
    val textLengthMax: Int?,
    val wordCountMin: Int?,
    val wordCountMax: Int?,
    val readingTimeMin: Int?,
    val readingTimeMax: Int?,
    val originalOnly: Int?,
    val genre: Int?,
    val workLang: String?,
    val replaceableOnly: Int?,
    val aiType: Int?,
    val r18Mode: R18Mode,
    val onlyAi: Boolean,
)

/**
 * [SearchModel]（app-api 那套参数名）→ 网页 ajax 的参数名。逐条对照：
 *
 * | app-api                | 网页            | 说明 |
 * |------------------------|-----------------|------|
 * | sort=date_desc         | order=date_d    | popular_desc/popular_preview/机内热度 一律 popular_d |
 * | search_target          | s_mode          | 小说侧 `s_tc` 是**正文**，标签+标题合并是 `s_tag` |
 * | start_date / end_date  | scd / ecd       | 相对档位同样当场算 today−N |
 * | bookmark_num_min/max   | blt / bgt       | |
 * | text_length_* 等 3 组   | tlt/tgt·wlt/wgt·rlt/rgt | |
 * | lang                   | work_lang       | |
 * | search_ai_type=1       | ai_type=1       | 同义（实测 aiType==2 被剔除） |
 *
 * 「Xusers入り」关键字后缀（[ceui.pixiv.ui.search.v3.KeywordUsersBucket]）照旧拼进 word，
 * 与 [ceui.lisa.repo.SearchNovelRepo.initApi] 一致。
 */
internal fun buildWebNovelSearchParams(searchModel: SearchModel): WebNovelSearchParams =
    buildWebNovelSearchParams(
        searchModel = searchModel,
        excludeAi = Shaft.sSettings.isDeleteAIIllust,
    )

internal fun buildWebNovelSearchParams(
    searchModel: SearchModel,
    keywordSnapshot: String,
): WebNovelSearchParams = buildWebNovelSearchParams(
    searchModel = searchModel,
    excludeAi = Shaft.sSettings.isDeleteAIIllust,
    keywordSnapshot = keywordSnapshot,
)

/**
 * 阈值显式传入的映射本体。抽出这层的理由与 [ceui.lisa.helper.IllustNovelFilter.judgeNovelSpam]
 * 同款：读 [Shaft.sSettings] 会触发 Application 子类的类初始化，在裸 JVM 单测里直接炸。
 * 见 WebNovelSearchParamsTest。
 */
internal fun buildWebNovelSearchParams(
    searchModel: SearchModel,
    excludeAi: Boolean,
    keywordSnapshot: String = searchModel.keyword.value.orEmpty(),
): WebNovelSearchParams {
    val bookmarkMin = searchModel.bookmarkMin.value?.takeIf { it > 0 }
    val bookmarkMax = searchModel.bookmarkMax.value?.takeIf { it > 0 }
    // 与 app-api 路径同一条规则：走了官方 bookmark 参数就不再拼关键字后缀，避免双重收紧。
    val suffix = if (bookmarkMin != null || bookmarkMax != null) "" else searchModel.starSize.value.orEmpty()
    val keyword = (keywordSnapshot + " " + suffix).trim()

    val (startDate, endDate) = resolveDateRange(searchModel)
    val r18Mode = when (searchModel.r18Restriction.value) {
        1 -> R18Mode.SafeOnly
        2 -> R18Mode.R18Only
        else -> R18Mode.All
    }
    val onlyAi = searchModel.onlyAi.value == true
    return WebNovelSearchParams(
        keyword = keyword,
        order = when (searchModel.sortType.value) {
            SortType.DATE_ASC -> "date"
            SortType.DATE_DESC -> "date_d"
            SortType.POPULAR_MALE_DESC -> "popular_male_d"
            SortType.POPULAR_FEMALE_DESC -> "popular_female_d"
            // popular_desc / popular_preview / 机内自带热度：网页只有一个 popular_d
            else -> "popular_d"
        },
        mode = when (r18Mode) {
            R18Mode.SafeOnly -> "safe"
            R18Mode.R18Only -> "r18"
            R18Mode.All -> "all"
        },
        sMode = when (searchModel.searchType.value) {
            "exact_match_for_tags" -> "s_tag_full"
            "text" -> "s_tc"
            // partial_match_for_tags / keyword / 空：都用网页的「标签 + 标题简介」合并档，
            // 与 app-api 默认档不传 search_target 时的合并行为对齐（见 SearchTarget.toQueryValue）
            else -> "s_tag"
        },
        startDate = startDate,
        endDate = endDate,
        bookmarkMin = bookmarkMin,
        bookmarkMax = bookmarkMax,
        textLengthMin = searchModel.textLengthMin.value,
        textLengthMax = searchModel.textLengthMax.value,
        wordCountMin = searchModel.wordCountMin.value,
        wordCountMax = searchModel.wordCountMax.value,
        readingTimeMin = searchModel.readingTimeMin.value,
        readingTimeMax = searchModel.readingTimeMax.value,
        originalOnly = if (searchModel.isOriginalOnly.value == true) 1 else null,
        genre = searchModel.genre.value,
        workLang = searchModel.lang.value,
        replaceableOnly = if (searchModel.isReplaceableOnly.value == true) 1 else null,
        // 屏蔽 AI 是全局设置（同 SearchNovelRepo.update）；「仅看 AI」官方无参数，服务端全返后
        // 在 toFeedItem 里按 aiType==2 客户端筛——此时绝不能同时发 ai_type=1，否则两边对夹清空。
        aiType = if (!onlyAi && excludeAi) 1 else null,
        r18Mode = r18Mode,
        onlyAi = onlyAi,
    )
}

/** 投稿期间：相对档当场算 today−N（跨午夜自动跟随今天），否则用「指定期间」的自定义起止。 */
private fun resolveDateRange(searchModel: SearchModel): Pair<String?, String?> {
    val bucket = searchModel.durationBucket.value?.let { name ->
        DurationBucket.values().firstOrNull { it.name == name }
    } ?: return searchModel.startDate.value to searchModel.endDate.value
    return bucket.toDateRange(LocalDate.now())
}
