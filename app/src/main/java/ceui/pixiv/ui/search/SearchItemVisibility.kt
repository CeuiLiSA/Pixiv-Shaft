package ceui.pixiv.ui.search

import androidx.lifecycle.ViewModel
import ceui.lisa.activities.Shaft
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust

/**
 * 二级搜索页（插画）网络入参快照（Spec）：
 * 包含所有真正决定 Pixiv 网络请求 URL / Query 的参数集合。
 *
 * 当新旧 Spec 相等时，代表「网络 URL 未变更」，此时条件变更（如切过滤已收藏、切 R-18 限制、
 * 切仅看 AI / 客户端屏蔽 AI、或重复提交相同搜索）应通过 [ceui.pixiv.feeds.FeedFragment.applyItemVisibility] 就地上下屏，
 * 而不是重新发起一次 [ceui.pixiv.feeds.FeedViewModel.refresh] 网络请求。
 */
data class IllustSearchNetworkSpec(
    val keyword: String,
    val starSize: String,
    val sortType: String?,
    val searchType: String?,
    val startDate: String?,
    val endDate: String?,
    val durationBucket: String?,
    val bookmarkMin: Int,
    val bookmarkMax: Int,
    val tool: String?,
    val lang: String?,
    val searchAiType: Int,
    /**
     * 「可能妨碍搜索的作品」（`include_potential_violation_works`）：null = 不传（显示），
     * false = 不显示。必须进 Spec——它改变请求 URL，切档要重新发请求，不能就地上下屏。
     */
    val includePotentialViolationWorks: Boolean?,
    val ratioPattern: String?,
    val contentType: String?,
    val widthMin: Int?,
    val widthMax: Int?,
    val heightMin: Int?,
    val heightMax: Int?,
)

/**
 * 二级搜索页（小说）网络入参快照（Spec）。
 */
data class NovelSearchNetworkSpec(
    val groupBySeries: Boolean,
    val r18RestrictionIfGrouped: Int?,
    val keyword: String,
    val starSize: String,
    val sortType: String?,
    val searchType: String?,
    val startDate: String?,
    val endDate: String?,
    val durationBucket: String?,
    val bookmarkMin: Int,
    val bookmarkMax: Int,
    val genre: Int?,
    val lang: String?,
    val searchAiType: Int,
    /** 「可能妨碍搜索的作品」—— 语义同 [IllustSearchNetworkSpec.includePotentialViolationWorks]。 */
    val includePotentialViolationWorks: Boolean?,
    val isOriginalOnly: Boolean?,
    val isReplaceableOnly: Boolean?,
    val textLengthMin: Int?,
    val textLengthMax: Int?,
    val wordCountMin: Int?,
    val wordCountMax: Int?,
    val readingTimeMin: Int?,
    val readingTimeMax: Int?,
)

/**
 * 本列表当前这一代首页**实际发出去**的网络入参（[IllustSearchNetworkSpec] / [NovelSearchNetworkSpec]）。
 *
 * 放在 Fragment 的 ViewModelStore 里，不挂在 Fragment 字段或 FeedSource 引用上：数据源只在
 * FeedViewModel 首次创建时造一次，深浅色 / 语言等配置变更重建 Fragment 后新实例拿不回它；
 * 这里与 FeedViewModel 同一个 store、同生命周期。
 */
class LoadedSearchSpec : ViewModel() {
    var spec: Any? = null
}

/**
 * 网络入参里 `include_potential_violation_works` 的取值——与
 * [ceui.lisa.repo.SearchIllustRepo] / [ceui.lisa.repo.SearchNovelRepo] 的归一化逐字一致：
 * 只有显式关掉才发 false，默认档发 null（不传）。两边口径一旦不同，Spec 会误判「URL 没变」
 * 而跳过重发——用户切了「不显示」却什么也没发生。
 */
private fun includePotentialViolationOf(searchModel: SearchModel): Boolean? =
    if (searchModel.includePotentialViolationWorks.value == false) false else null

/**
 * 从当前 [SearchModel] 构造插画搜索的网络入参快照。
 *
 * [keyword] 由数据源传本代首页的 keyword 快照：SearchModel.keyword 随键入实时变，必须与
 * repo.update 读的是同一份。
 */
fun buildIllustNetworkSpec(
    searchModel: SearchModel,
    keyword: String = searchModel.keyword.value.orEmpty(),
): IllustSearchNetworkSpec {
    val onlyAi = searchModel.onlyAi.value == true
    val excludeAi = searchModel.sessionExcludeAi.value ?: (runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false)
    val clientSideAi = onlyAi || (excludeAi && (runCatching { Shaft.sSettings?.isAiBlockClientSide }.getOrNull() ?: false))
    val searchAiType = if (clientSideAi) 0 else if (excludeAi) 1 else 0

    return IllustSearchNetworkSpec(
        keyword = keyword,
        starSize = searchModel.starSize.value.orEmpty(),
        sortType = searchModel.sortType.value,
        searchType = searchModel.searchType.value,
        startDate = searchModel.startDate.value,
        endDate = searchModel.endDate.value,
        durationBucket = searchModel.durationBucket.value,
        bookmarkMin = searchModel.bookmarkMin.value ?: 0,
        bookmarkMax = searchModel.bookmarkMax.value ?: 0,
        tool = searchModel.tool.value,
        lang = searchModel.lang.value,
        searchAiType = searchAiType,
        includePotentialViolationWorks = includePotentialViolationOf(searchModel),
        ratioPattern = searchModel.ratioPattern.value,
        contentType = searchModel.contentType.value,
        widthMin = searchModel.widthMin.value,
        widthMax = searchModel.widthMax.value,
        heightMin = searchModel.heightMin.value,
        heightMax = searchModel.heightMax.value,
    )
}

/**
 * 从当前 [SearchModel] 构造小说搜索的网络入参快照。[keyword] 同 [buildIllustNetworkSpec]。
 */
fun buildNovelNetworkSpec(
    searchModel: SearchModel,
    keyword: String = searchModel.keyword.value.orEmpty(),
): NovelSearchNetworkSpec {
    val groupBySeries = searchModel.groupBySeries.value == true
    val onlyAi = searchModel.onlyAi.value == true
    val excludeAi = searchModel.sessionExcludeAi.value ?: (runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false)
    val clientSideAi = onlyAi || (excludeAi && (runCatching { Shaft.sSettings?.isAiBlockClientSide }.getOrNull() ?: false))
    val searchAiType = if (clientSideAi) 0 else if (excludeAi) 1 else 0

    return NovelSearchNetworkSpec(
        groupBySeries = groupBySeries,
        r18RestrictionIfGrouped = if (groupBySeries) (searchModel.r18Restriction.value ?: 0) else null,
        keyword = keyword,
        starSize = searchModel.starSize.value.orEmpty(),
        sortType = searchModel.sortType.value,
        searchType = searchModel.searchType.value,
        startDate = searchModel.startDate.value,
        endDate = searchModel.endDate.value,
        durationBucket = searchModel.durationBucket.value,
        bookmarkMin = searchModel.bookmarkMin.value ?: 0,
        bookmarkMax = searchModel.bookmarkMax.value ?: 0,
        genre = searchModel.genre.value,
        lang = searchModel.lang.value,
        searchAiType = searchAiType,
        includePotentialViolationWorks = includePotentialViolationOf(searchModel),
        isOriginalOnly = if (searchModel.isOriginalOnly.value == true) true else null,
        isReplaceableOnly = if (searchModel.isReplaceableOnly.value == true) true else null,
        textLengthMin = searchModel.textLengthMin.value,
        textLengthMax = searchModel.textLengthMax.value,
        wordCountMin = searchModel.wordCountMin.value,
        wordCountMax = searchModel.wordCountMax.value,
        readingTimeMin = searchModel.readingTimeMin.value,
        readingTimeMax = searchModel.readingTimeMax.value,
    )
}

/**
 * 搜索插画条目的就地显隐判定。
 *
 * @param bookmarkFilter 是否开启搜索「过滤已收藏」
 * @param r18Restriction 搜索 R-18 限制档位（0=全部，1=仅安全，2=仅 R-18）
 * @param globalR18FilterOn 全局「R-18 过滤」临时开关
 * @param onlyAi 是否开启「仅看 AI」
 * @param excludeAi 是否开启「屏蔽 AI」
 */
fun shouldHideSearchIllust(
    illust: Illust,
    bookmarkFilter: Boolean,
    r18Restriction: Int,
    globalR18FilterOn: Boolean,
    onlyAi: Boolean = false,
    excludeAi: Boolean = false,
): Boolean {
    if (bookmarkFilter && illust.isBookmarked) return true
    val isR18File = illust.isR18File()
    val r18Hidden = when (r18Restriction) {
        1 -> isR18File || (globalR18FilterOn && IllustNovelFilter.hitsR18Filter(illust))
        2 -> !isR18File
        else -> globalR18FilterOn && IllustNovelFilter.hitsR18Filter(illust)
    }
    if (r18Hidden) return true

    if (onlyAi) {
        return !IllustNovelFilter.isAiWork(illust)
    }
    if (excludeAi) {
        if (IllustNovelFilter.shouldHideAi(illust, true)) return true
        val canBlur = runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false
        if (!canBlur && IllustNovelFilter.shouldBlurAi(illust, true)) return true
    }
    return false
}

/**
 * 搜索小说条目的就地显隐判定。
 *
 * @param supportsBlur 该小说展示类型是否支持模糊打码（普通单篇卡支持，系列归纳卡无打码层不支持）
 */
fun shouldHideSearchNovel(
    novel: Novel,
    bookmarkFilter: Boolean,
    r18Restriction: Int,
    globalR18FilterOn: Boolean,
    onlyAi: Boolean = false,
    excludeAi: Boolean = false,
    supportsBlur: Boolean = true,
): Boolean {
    if (bookmarkFilter && novel.is_bookmarked == true) return true
    val isR18File = (novel.x_restrict ?: 0) > 0
    val r18Hidden = when (r18Restriction) {
        1 -> isR18File || (globalR18FilterOn && IllustNovelFilter.hitsR18Filter(novel))
        2 -> !isR18File
        else -> globalR18FilterOn && IllustNovelFilter.hitsR18Filter(novel)
    }
    if (r18Hidden) return true

    if (onlyAi) {
        return !IllustNovelFilter.isAiWork(novel)
    }
    if (excludeAi) {
        if (IllustNovelFilter.shouldHideAi(novel, true)) return true
        val canBlur = supportsBlur && (runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false)
        if (!canBlur && IllustNovelFilter.shouldBlurAi(novel, true)) return true
    }
    return false
}
