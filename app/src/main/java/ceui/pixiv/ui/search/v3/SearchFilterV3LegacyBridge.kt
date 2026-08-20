package ceui.pixiv.ui.search.v3

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.observeEvent
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.ui.search.SortType
import java.util.WeakHashMap

/**
 * 把 V3 搜索筛选器接进老版本 [ceui.lisa.activities.SearchActivity] 的桥。
 *
 * 老搜索的真单源是 Java [SearchModel]：[ceui.lisa.fragments.FragmentSearchIllust] /
 * [ceui.lisa.fragments.FragmentSearchNovel] 都是观察 `searchModel.nowGo` 触发刷新，再读
 * `searchModel.{sortType, searchType, starSize, startDate, endDate, r18Restriction}` 当做参数。
 *
 * V3 sheet 的真单源是 Kotlin [SearchViewModel] 的 `illustFilter` / `novelFilter` LiveData。
 * 这个 bridge 做两件事：
 *   1. 启动时把 SearchModel 的现状种到 SearchViewModel —— 这样 sheet 第一次打开看到的是
 *      用户上次留下的筛选状态，而不是默认值。
 *   2. 持续观察 SearchViewModel 的 filter 变化，原子地翻成 SearchModel 字段；当 sheet 触发
 *      搜索事件时，给 SearchModel.nowGo 一脚，老 fragment 就刷新。
 *
 * 全部 V3 维度（tool / lang / genre / duration / bookmark / 仅原创 / 仅单词置换）legacy AppApi
 * 已经扩展支持，bridge 全字段双向翻译；老版与新版功能完全对齐。
 */
object SearchFilterV3LegacyBridge {

    /**
     * 已安装过 bridge 的 activity 集合——activity 死亡时自动清理（DefaultLifecycleObserver
     * 监听 onDestroy）。WeakHashMap 兜底——避免活引用 activity，杜绝 leak。
     */
    private val installed: MutableSet<AppCompatActivity> =
        java.util.Collections.newSetFromMap(WeakHashMap())

    /** Activity-scoped 工厂——SearchViewModel 没无参 ctor，得显式提供。 */
    fun resolveSearchViewModel(activity: AppCompatActivity): SearchViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        return ViewModelProvider(activity, factory)[SearchViewModel::class.java]
    }

    /**
     * 在 [activity] 上安装 bridge。activity 应在 onCreate / initData 末尾调一次。
     * 重复调用静默 no-op；activity onDestroy 时自动从 [installed] 摘除。
     */
    fun install(activity: AppCompatActivity, searchModel: SearchModel) {
        if (!installed.add(activity)) return
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                installed.remove(activity)
            }
        })
        val vm = resolveSearchViewModel(activity)

        // ── 1. seed：SearchModel → SearchViewModel.filter ──
        // SearchViewModel.illust/novelFilter 默认值已经走 [SearchFilterV3.fromGlobalDefaults]，
        // 把 sort/starSize/AI 三个全局偏好读进来；这里再用 SearchModel 的会话状态（用户在
        // 老 fragment 里改的）覆盖默认值。仅在 VM 还是「全局默认」状态时种一次，避免覆盖
        // 用户在 sheet 内的临时改动。
        // novel 的基线经过 novelSafe 归一（设置里可能存着 illust 专属的男/女性向人气），
        // 与 SearchViewModel.novelFilter 的初始值保持同一构造，否则这里永远判不相等、seed 失效。
        if (vm.illustFilter.value == SearchFilterV3.fromGlobalDefaults()) {
            vm.illustFilter.value = seedFromLegacy(searchModel, isNovel = false)
        }
        if (vm.novelFilter.value == SearchFilterV3.fromGlobalDefaults(forNovel = true)) {
            vm.novelFilter.value = seedFromLegacy(searchModel, isNovel = true)
        }

        // ── 2. observe：SearchViewModel.filter 变化 → 翻译到 SearchModel ──
        vm.illustFilter.observe(activity) { filter ->
            if (filter != null) applyToLegacy(filter, searchModel)
        }
        vm.novelFilter.observe(activity) { filter ->
            if (filter != null) applyToLegacy(filter, searchModel)
        }

        // ── 3. 搜索事件 → searchModel.nowGo 触发老 fragment 刷新 ──
        vm.searchIllustMangaEvent.observeEvent(activity) {
            // 切到 illust 当前 filter 再写一遍，确保即时性
            vm.illustFilter.value?.let { applyToLegacy(it, searchModel) }
            searchModel.nowGo.value = "search_now"
        }
        vm.searchNovelEvent.observeEvent(activity) {
            vm.novelFilter.value?.let { applyToLegacy(it, searchModel) }
            searchModel.nowGo.value = "search_now"
        }
    }

    /**
     * 从 [SearchModel] 当前值构造一个 [SearchFilterV3]。fields 一对一翻：
     *  - `bookmarkMin/Max`       → bookmarkRange（官方 query 参数路径）
     *  - `starSize` ("Xusers入り") → keywordUsersBucket（关键字后缀路径，对非会员有效；
     *                              与 bookmarkRange 互不屏蔽）
     *  - `searchType`            → searchTarget（不识别就回 partial）
     *  - `sortType`              → sort（不识别就回 popular_preview）
     *  - `startDate / endDate`   → 同名字段
     *  - `r18Restriction`        → R18Mode（0/1/2 ↔ All/SafeOnly/R18Only）
     *  - AI 三档：「屏蔽AI」由 [ceui.lisa.activities.Shaft.sSettings.isDeleteAIIllust] 全局读（baseline）；
     *    「仅看AI」是临时维度，不入设置，存在 [SearchModel.onlyAi] 会话态里，这里读回来还原档位。
     */
    private fun seedFromLegacy(searchModel: SearchModel, isNovel: Boolean): SearchFilterV3 {
        // baseline = Shaft.sSettings 三项偏好；下面任一字段在 SearchModel 里有值就 override，
        // 没值就回退到 baseline。SearchModel 全空 → 与 baseline 等价。
        val baseline = SearchFilterV3.fromGlobalDefaults(forNovel = isNovel)

        // SearchModel.sortType 是 illust/novel 共享单字段——插画侧写进去的男/女性向人气
        // 不能种进 novel filter（novel 端点不识别），归一到总热度。
        val sort = (searchModel.sortType.value ?: baseline.sort)
            .let { if (isNovel) SortType.novelSafe(it)!! else it }
        val target = SearchTarget.values().firstOrNull { it.apiValue == searchModel.searchType.value }
            ?: baseline.searchTarget

        // 两条桶独立解析：bookmarkMin/Max 走官方 query，starSize 走关键字后缀。
        // 区间不再对照静态枚举反查——自定义档（「指定点赞数」）也要原样还原。
        val storedMin = searchModel.bookmarkMin.value?.takeIf { it > 0 }
        val storedMax = searchModel.bookmarkMax.value?.takeIf { it > 0 }
        val bookmarkRange = if (storedMin != null || storedMax != null)
            BookmarkRangeSpec(storedMin, storedMax)
        else baseline.bookmarkRange
        val parsedStar = parseStarSizeMin(searchModel.starSize.value)
        val keywordBucket = if (parsedStar > 0)
            KeywordUsersBucket.values().firstOrNull { it.min == parsedStar } ?: baseline.keywordUsersBucket
        else baseline.keywordUsersBucket

        val r18 = when (searchModel.r18Restriction.value) {
            1 -> R18Mode.SafeOnly
            2 -> R18Mode.R18Only
            else -> baseline.r18Mode
        }
        val ratio = if (isNovel) null
            else RatioPattern.values().firstOrNull { it.apiValue == searchModel.ratioPattern.value }
                ?: baseline.ratioPattern
        // 作品类别（仅 illust/manga）：反向解析 SearchModel.contentType (apiValue) → enum；
        // 命中不到（含 null）→ 默认 IllustAndMangaAndUgoira
        val contentType = if (isNovel) IllustContentType.IllustAndMangaAndUgoira
            else IllustContentType.values().firstOrNull { it.apiValue == searchModel.contentType.value }
                ?: baseline.contentType
        // 反向解析分辨率档位：以 4 个 query 参数的组合命中固定枚举值；命中不到 = 全部清晰度
        val resolution = if (isNovel) null else {
            val wMin = searchModel.widthMin.value
            val wMax = searchModel.widthMax.value
            val hMin = searchModel.heightMin.value
            val hMax = searchModel.heightMax.value
            ResolutionBucket.values().firstOrNull {
                it.widthMin == wMin && it.widthMax == wMax &&
                    it.heightMin == hMin && it.heightMax == hMax
            } ?: baseline.resolutionBucket
        }
        // 正文长度：text / word / reading-time 三选一；任意一组设了就构造对应单位的 spec。
        // 三组同时存了的话按优先级 char > word > reading-time 取第一非空。
        val bodyLength = if (!isNovel) null else {
            val tMin = searchModel.textLengthMin.value
            val tMax = searchModel.textLengthMax.value
            val wMin = searchModel.wordCountMin.value
            val wMax = searchModel.wordCountMax.value
            val rMin = searchModel.readingTimeMin.value
            val rMax = searchModel.readingTimeMax.value
            when {
                tMin != null || tMax != null -> BodyLengthSpec(BodyLengthUnit.Char, tMin, tMax)
                wMin != null || wMax != null -> BodyLengthSpec(BodyLengthUnit.Word, wMin, wMax)
                rMin != null || rMax != null -> BodyLengthSpec(BodyLengthUnit.ReadingTime, rMin, rMax)
                else -> baseline.bodyLength
            }
        }

        return SearchFilterV3(
            sort = sort,
            searchTarget = target,
            bookmarkRange = bookmarkRange,
            keywordUsersBucket = keywordBucket,
            tool = if (isNovel) null else (searchModel.tool.value ?: baseline.tool),
            genre = if (isNovel) (searchModel.genre.value ?: baseline.genre) else null,
            lang = searchModel.lang.value ?: baseline.lang,
            // bucket 名字反解到 enum；命中不到（含 null）→ baseline
            durationBucket = searchModel.durationBucket.value?.let { name ->
                DurationBucket.values().firstOrNull { it.name == name }
            } ?: baseline.durationBucket,
            startDate = searchModel.startDate.value ?: baseline.startDate,
            endDate = searchModel.endDate.value ?: baseline.endDate,
            r18Mode = r18,
            // AI 三档：「屏蔽AI」以全局设置为准（baseline，OtherFilterSheet 提交时已落盘）；
            // 「仅看AI」是会话临时态，从 SearchModel.onlyAi 读回还原（activity 重建后 VM 为空时也不丢）。
            aiMode = if (searchModel.onlyAi.value == true) AiMode.OnlyAi else baseline.aiMode,
            isOriginalOnly = isNovel && searchModel.isOriginalOnly.value == true,
            isReplaceableOnly = isNovel && searchModel.isReplaceableOnly.value == true,
            groupBySeries = isNovel && searchModel.groupBySeries.value == true,
            ratioPattern = ratio,
            resolutionBucket = resolution,
            contentType = contentType,
            bodyLength = bodyLength,
        )
    }

    private fun parseStarSizeMin(starSize: String?): Int {
        if (starSize.isNullOrEmpty()) return 0
        val match = Regex("""\d+""").find(starSize) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }

    /**
     * SearchFilterV3 → SearchModel。两条收藏量维度独立：
     *  - `bookmarkRange`      → `searchModel.bookmarkMin/Max`，走官方 `bookmark_num_min/max` query 参数；
     *  - `keywordUsersBucket` → `searchModel.starSize`，作为关键字后缀拼到 query 里
     *    （[SearchIllustRepo.initApi] 内拼接）。
     * 两条可以同时生效（会员选 bookmark_num_min；非会员只能靠 keyword hack）。
     *
     * AI 三档：「屏蔽AI」不写 SearchModel——它通过全局 [ceui.lisa.activities.Shaft.sSettings.isDeleteAIIllust]
     * 生效（[OtherFilterSheet] 提交时已落盘，Repo.update 也读全局）。「仅看AI」官方无参数，写到
     * [SearchModel.onlyAi] 会话态，Repo.update 读它喂给 FilterMapper 做客户端过滤（issue #909）。
     */
    private fun applyToLegacy(filter: SearchFilterV3, searchModel: SearchModel) {
        searchModel.sortType.value = filter.sort
        searchModel.searchType.value = filter.searchTarget.apiValue
        // 两条桶分别落到 starSize / bookmarkMin+Max —— Repo 都读，互不屏蔽
        searchModel.starSize.value = filter.keywordUsersBucket.keywordSuffix()
        searchModel.bookmarkMin.value = filter.bookmarkRange?.min
        searchModel.bookmarkMax.value = filter.bookmarkRange?.max
        searchModel.tool.value = filter.tool
        searchModel.genre.value = filter.genre
        searchModel.lang.value = filter.lang
        // 投稿期间:bucket 名字写到 SearchModel.durationBucket,Repo.initApi 当场算 today−N
        // (跨午夜窗口自动跟随今天);bucket 非空时 start/end_date 互斥清空。
        // null bucket → bucket 字段清空,start/end_date 用 filter 原值(指定期间自定义/不限)
        searchModel.durationBucket.value = filter.durationBucket?.name
        if (filter.durationBucket != null) {
            searchModel.startDate.value = null
            searchModel.endDate.value = null
        } else {
            searchModel.startDate.value = filter.startDate
            searchModel.endDate.value = filter.endDate
        }
        searchModel.r18Restriction.value = when (filter.r18Mode) {
            R18Mode.All -> 0
            R18Mode.SafeOnly -> 1
            R18Mode.R18Only -> 2
        }
        // 「仅看AI」会话态：Repo.update 读它喂 FilterMapper 客户端过滤（屏蔽AI 仍走全局设置）
        searchModel.onlyAi.value = filter.aiMode == AiMode.OnlyAi
        searchModel.isOriginalOnly.value = filter.isOriginalOnly
        searchModel.isReplaceableOnly.value = filter.isReplaceableOnly
        // 系列归纳仅 novel；illust filter 恒 false 写下来无副作用（插画路径不读它），真正生效的
        // 那次写入由 searchNovelEvent 在触发 nowGo 之前补（见 install 的第 3 步）。
        searchModel.groupBySeries.value = filter.groupBySeries
        // ratio_pattern 仅 illust/manga；novel 路径 SearchIllustRepo 不读，写入 SearchModel 也无副作用
        searchModel.ratioPattern.value = filter.ratioPattern?.apiValue
        // 作品类别仅 illust/manga；默认档「插画、漫画、动图」等价于不传 → null,与 buildSearchConfig 对齐
        searchModel.contentType.value = if (filter.contentType == IllustContentType.IllustAndMangaAndUgoira)
            null else filter.contentType.apiValue
        // 分辨率档位 4 项一并落到 SearchModel；null bucket → 4 个全 null（不传 query）
        searchModel.widthMin.value = filter.resolutionBucket?.widthMin
        searchModel.widthMax.value = filter.resolutionBucket?.widthMax
        searchModel.heightMin.value = filter.resolutionBucket?.heightMin
        searchModel.heightMax.value = filter.resolutionBucket?.heightMax
        // 正文长度：unit 三选一分派到对应组；另两组一律清空，避免之前留下的值串味儿
        val isChar = filter.bodyLength?.unit == BodyLengthUnit.Char
        val isWord = filter.bodyLength?.unit == BodyLengthUnit.Word
        val isRead = filter.bodyLength?.unit == BodyLengthUnit.ReadingTime
        searchModel.textLengthMin.value = if (isChar) filter.bodyLength?.min else null
        searchModel.textLengthMax.value = if (isChar) filter.bodyLength?.max else null
        searchModel.wordCountMin.value = if (isWord) filter.bodyLength?.min else null
        searchModel.wordCountMax.value = if (isWord) filter.bodyLength?.max else null
        searchModel.readingTimeMin.value = if (isRead) filter.bodyLength?.min else null
        searchModel.readingTimeMax.value = if (isRead) filter.bodyLength?.max else null
    }
}
