package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterRowBinding
import ceui.lisa.databinding.DialogSearchFilterV3Binding
import ceui.loxia.Client
import ceui.loxia.ObjectType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.ui.search.SortType
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 Search Filter —— pixiv iOS 8.6.5「搜索条件」的 V3 投影。
 *
 * 视觉：top bar（取消/标题）+ 三段 settings card（A 段 检索范围/排序；
 * B 段 投稿期间/喜欢！数/工具|类型/语种 [+ novel 仅限原创/单词置换]；C 段 其他条件）+
 * 底部全宽 V3 primary pill「搜索」。
 *
 * 双模式（[ARG_LEGACY]）：
 *   - 默认 false：宿主是新版搜索 [ceui.pixiv.ui.search.SearchViewPagerFragment]；
 *     ViewModelStoreOwner = parentFragment.parentFragment。
 *   - true：宿主是 [ceui.lisa.activities.SearchActivity]；ViewModelStoreOwner = activity；
 *     [SearchFilterV3LegacyBridge] 把 SearchViewModel.illustFilter / novelFilter 翻译回老
 *     SearchModel，老 fragment 通过 nowGo 触发刷新。
 *
 * picker 走 FragmentResult API（[SimplePickerSheet] / [DurationPickerSheet] /
 * [OtherFilterSheet]）—— 跨 config change 不丢回调。监听器全部在 [onViewCreated]
 * 一次性注册，每次 view 重建都会重新登记，缓存中的 fragment result 自动派发。
 */
class SearchFilterV3BottomSheet : V3BottomSheetBase() {

    override val maxHeightFraction: Float = 0.92F

    private val isLegacy: Boolean
        get() = arguments?.getBoolean(ARG_LEGACY, false) ?: false

    /** internal so child sheets (e.g. [OtherFilterSheet]) can read live VM state. */
    internal val searchViewModel: SearchViewModel by lazy {
        val owner: ViewModelStoreOwner = if (isLegacy) {
            requireActivity()
        } else {
            try { requireParentFragment().requireParentFragment() }
            catch (_: Throwable) { requireParentFragment() }
        }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        ViewModelProvider(owner, factory)[SearchViewModel::class.java]
    }

    private val objectType: String
        get() = arguments?.getString(ARG_OBJECT_TYPE) ?: ObjectType.ILLUST

    private val isNovel: Boolean get() = objectType == ObjectType.NOVEL

    private fun currentFilter(): SearchFilterV3 =
        if (isNovel) (searchViewModel.novelFilter.value ?: SearchFilterV3())
        else (searchViewModel.illustFilter.value ?: SearchFilterV3())

    private fun updateFilter(transform: (SearchFilterV3) -> SearchFilterV3) {
        val store = if (isNovel) searchViewModel.novelFilter else searchViewModel.illustFilter
        store.value = transform(currentFilter())
        renderRows()
    }

    private var _binding: DialogSearchFilterV3Binding? = null
    private val binding get() = _binding!!

    // 静态可枚举的 picker 候选；动态的（tool/genre/lang）由 VM.searchOptions 派生。
    //
    // 男性向 / 女性向人气两档是 pixiv 官方插画/漫画专属 + 仅 Premium 用户可用的排序
    // （novel endpoint 不识别；非会员选了实际服务端也不接受，issue #575）。
    // 所以仅在 illust/manga 模式且当前账号是 Premium 时才让两档出现在 picker 里。
    // 兜底：非会员万一拿到了带有该 sort 的 filter（比如老 SearchModel 状态种回来），
    // [shouldUsePopularPreview] 会把它路由到 popular-preview endpoint，不会 400。
    private val sortList: List<String>
        get() = buildList {
            add(SortType.POPULAR_PREVIEW)
            add(SortType.DATE_DESC)
            add(SortType.DATE_ASC)
            add(SortType.POPULAR_DESC)
            if (!isNovel && SessionManager.isPremium) {
                add(SortType.POPULAR_MALE_DESC)
                add(SortType.POPULAR_FEMALE_DESC)
            }
        }
    private val bookmarkList = BookmarkBucket.values().toList()
    private val keywordUsersList = KeywordUsersBucket.values().toList()
    // 长宽比候选；index 0 = "所有纵横比"（null），1.. = RatioPattern.values()[idx-1]
    private val ratioList = RatioPattern.values().toList()
    // 分辨率档位；index 0 = "全部清晰度"（null），1.. = ResolutionBucket.values()[idx-1]
    private val resolutionList = ResolutionBucket.values().toList()
    // 作品类别候选（5 档，IllustContentType.IllustAndMangaAndUgoira 为默认）
    private val contentTypeList = IllustContentType.values().toList()
    // 正文长度 / 阅读用时（novel 专属）枚举
    private val charLengthList = CharLengthBucket.values().toList()
    private val wordLengthList = WordLengthBucket.values().toList()
    private val readingTimeBucketList = ReadingTimeBucket.values().toList()
    private val targetList: List<SearchTarget>
        get() = if (isNovel) SearchTarget.forNovel() else SearchTarget.forIllust()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterV3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // accent 色（取消按钮 / 行右侧值 / 搜索按钮）—— V3Palette 派生于主题 colorPrimary
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnSearch.background =
            palette.pillPrimary(999f * resources.displayMetrics.density)
        binding.btnSearch.setOnClick {
            triggerRefresh()
            dismissAllowingStateLoss()
        }
        listOf(
            binding.rowTarget,
            binding.rowSort,
            binding.rowContentType,
            binding.rowDuration,
            binding.rowBookmark,
            binding.rowKeywordBookmark,
            binding.rowToolOrGenre,
            binding.rowLang,
            binding.rowRatio,
            binding.rowResolution,
            binding.rowBodyLength,
            binding.rowOther,
        ).forEach { it.rowValue.setTextColor(palette.textAccent) }

        // 行点击路由
        binding.rowTarget.root.setOnClick { showTargetPicker() }
        binding.rowSort.root.setOnClick { showSortPicker() }
        binding.rowContentType.root.setOnClick { showContentTypePicker() }
        binding.rowDuration.root.setOnClick { showDurationPicker() }
        binding.rowBookmark.root.setOnClick { showBookmarkPicker() }
        binding.rowKeywordBookmark.root.setOnClick { showKeywordBookmarkPicker() }
        // illust 模式整行隐藏(制图工具已搬到「其他条件」),只 novel 时点开 → 类型 picker
        binding.rowToolOrGenre.root.setOnClick {
            if (isNovel) showGenrePicker()
        }
        binding.rowLang.root.setOnClick { showLangPicker() }
        binding.rowRatio.root.setOnClick { showRatioPicker() }
        binding.rowResolution.root.setOnClick { showResolutionPicker() }
        binding.rowBodyLength.root.setOnClick { showBodyLengthPicker() }
        binding.rowOther.root.setOnClick { showOtherSheet() }

        // 语种行仅 novel 展示；illust/manga 不需要语种维度
        binding.dividerLang.isVisible = isNovel
        binding.rowLang.root.isVisible = isNovel
        // 制图工具 / 类型 共用同一行：illust 把制图工具搬到「其他条件」sheet,这里整行 + divider 隐藏;
        // novel 保留作为类型入口
        binding.dividerToolOrGenre.isVisible = isNovel
        binding.rowToolOrGenre.root.isVisible = isNovel
        // 长宽比 + 分辨率仅 illust/manga 展示；novel 模式整行 + divider 隐藏
        binding.dividerRatio.isVisible = !isNovel
        binding.rowRatio.root.isVisible = !isNovel
        binding.dividerResolution.isVisible = !isNovel
        binding.rowResolution.root.isVisible = !isNovel
        // 正文长度仅 novel 展示（文字数 / 单词数 / 阅读用时 三种单位在一个 picker 里）
        binding.dividerBodyLength.isVisible = isNovel
        binding.rowBodyLength.root.isVisible = isNovel
        // 作品类别仅 illust/manga 展示；novel 模式整行 + divider 隐藏
        binding.dividerContentType.isVisible = !isNovel
        binding.rowContentType.root.isVisible = !isNovel

        registerPickerListeners(viewLifecycleOwner)
        renderRows()
        ensureSearchOptionsLoaded()
    }

    // ──────────────────────────────────────────────────────────────────
    // Fragment result listeners —— picker 提交结果走这里。
    // ──────────────────────────────────────────────────────────────────

    private fun registerPickerListeners(lifecycleOwner: LifecycleOwner) {
        val fm = childFragmentManager

        fm.setFragmentResultListener(REQUEST_TARGET, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            targetList.getOrNull(idx)?.let { tgt -> updateFilter { it.copy(searchTarget = tgt) } }
        }
        fm.setFragmentResultListener(REQUEST_SORT, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            sortList.getOrNull(idx)?.let { s -> updateFilter { it.copy(sort = s) } }
        }
        fm.setFragmentResultListener(REQUEST_BOOKMARK, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            bookmarkList.getOrNull(idx)?.let { b -> updateFilter { it.copy(bookmarkBucket = b) } }
        }
        fm.setFragmentResultListener(REQUEST_KEYWORD_BOOKMARK, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            keywordUsersList.getOrNull(idx)?.let { b ->
                updateFilter { it.copy(keywordUsersBucket = b) }
            }
        }
        fm.setFragmentResultListener(REQUEST_GENRE, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
            updateFilter { it.copy(genre = if (idx == 0) null else opts.getOrNull(idx - 1)?.id) }
        }
        fm.setFragmentResultListener(REQUEST_LANG, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val opts = if (isNovel) searchViewModel.searchOptions.value?.novel?.lang?.options.orEmpty()
                       else searchViewModel.searchOptions.value?.illust?.lang?.options.orEmpty()
            updateFilter { it.copy(lang = if (idx == 0) null else opts.getOrNull(idx - 1)?.code) }
        }
        fm.setFragmentResultListener(REQUEST_RATIO, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            // idx 0 = "所有纵横比"（null），1.. = ratioList[idx-1]
            updateFilter {
                it.copy(ratioPattern = if (idx == 0) null else ratioList.getOrNull(idx - 1))
            }
        }
        fm.setFragmentResultListener(REQUEST_RESOLUTION, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            // idx 0 = "全部清晰度"（null），1.. = resolutionList[idx-1]
            updateFilter {
                it.copy(resolutionBucket = if (idx == 0) null else resolutionList.getOrNull(idx - 1))
            }
        }
        fm.setFragmentResultListener(REQUEST_CONTENT_TYPE, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            // contentTypeList 直接索引到枚举值；默认档 IllustAndMangaAndUgoira 在 0 位
            contentTypeList.getOrNull(idx)?.let { ct ->
                updateFilter { it.copy(contentType = ct) }
            }
        }
        fm.setFragmentResultListener(REQUEST_BODY_LENGTH, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            handleBodyLengthPick(idx)
        }
        fm.setFragmentResultListener(REQUEST_BODY_LENGTH_CUSTOM_CHAR, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(NumberRangeInputSheet.KEY_PATCH)
                as? NumberRangeInputSheet.Patch ?: return@setFragmentResultListener
            updateFilter {
                it.copy(bodyLength = if (patch.min == null && patch.max == null) null
                    else BodyLengthSpec(BodyLengthUnit.Char, patch.min, patch.max))
            }
        }
        fm.setFragmentResultListener(REQUEST_BODY_LENGTH_CUSTOM_WORD, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(NumberRangeInputSheet.KEY_PATCH)
                as? NumberRangeInputSheet.Patch ?: return@setFragmentResultListener
            updateFilter {
                it.copy(bodyLength = if (patch.min == null && patch.max == null) null
                    else BodyLengthSpec(BodyLengthUnit.Word, patch.min, patch.max))
            }
        }
        fm.setFragmentResultListener(REQUEST_BODY_LENGTH_CUSTOM_TIME, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(NumberRangeInputSheet.KEY_PATCH)
                as? NumberRangeInputSheet.Patch ?: return@setFragmentResultListener
            updateFilter {
                it.copy(bodyLength = if (patch.min == null && patch.max == null) null
                    else BodyLengthSpec(BodyLengthUnit.ReadingTime, patch.min, patch.max))
            }
        }
        fm.setFragmentResultListener(REQUEST_DURATION, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(DurationPickerSheet.KEY_PATCH)
                    as? DurationPickerSheet.Patch ?: return@setFragmentResultListener
            if (patch.openCustomRange) {
                // 选了「指定期间」→ 不动 filter，直接弹 DateRangePickerSheet 让用户填日期
                showDateRangePicker()
            } else {
                // 选了某个相对档或「不限」—— 自定义日期一并清掉，三者互斥
                updateFilter {
                    it.copy(durationBucket = patch.bucket, startDate = null, endDate = null)
                }
            }
        }
        fm.setFragmentResultListener(REQUEST_DURATION_DATES, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(DateRangePickerSheet.KEY_PATCH)
                    as? DateRangePickerSheet.Patch ?: return@setFragmentResultListener
            // 用户在「指定期间」sheet 按确定 → 用 custom 起止覆盖；bucket 互斥清空
            updateFilter {
                it.copy(
                    durationBucket = null,
                    startDate = patch.startDate,
                    endDate = patch.endDate,
                )
            }
        }
        fm.setFragmentResultListener(REQUEST_OTHER, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(OtherFilterSheet.KEY_PATCH)
                    as? OtherFilterSheet.Patch ?: return@setFragmentResultListener
            updateFilter {
                it.copy(
                    aiMode = patch.aiMode,
                    r18Mode = patch.r18Mode,
                    isOriginalOnly = patch.isOriginalOnly,
                    isReplaceableOnly = patch.isReplaceableOnly,
                    tool = patch.tool,
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Row 文案绑定 —— 状态变化或拉到 search options 后调一次。
    // ──────────────────────────────────────────────────────────────────

    private fun renderRows() {
        if (_binding == null) return
        val filter = currentFilter()

        bindRow(binding.rowTarget, R.string.search_filter_v3_row_target,
            searchTargetLabel(filter.searchTarget))
        bindRow(binding.rowSort, R.string.search_filter_v3_row_sort, sortLabel(filter.sort))
        if (!isNovel) {
            bindRow(binding.rowContentType, R.string.search_filter_v3_row_content_type,
                contentTypeLabel(filter.contentType))
        }
        bindRow(binding.rowDuration, R.string.search_filter_v3_row_duration, durationSummary(filter))
        bindRow(binding.rowBookmark, R.string.search_filter_v3_row_bookmark, bookmarkLabel(filter.bookmarkBucket))
        bindRow(
            binding.rowKeywordBookmark,
            R.string.search_filter_v3_row_keyword_bookmark,
            keywordBookmarkLabel(filter.keywordUsersBucket),
        )
        if (isNovel) {
            bindRow(binding.rowToolOrGenre, R.string.search_filter_v3_row_genre, genreSummary(filter))
        }
        // illust 模式 rowToolOrGenre 整行隐藏,不用 bind;制图工具入口在「其他条件」sheet
        bindRow(binding.rowLang, R.string.search_filter_v3_row_lang, langSummary(filter))
        bindRow(binding.rowRatio, R.string.search_filter_v3_row_ratio, ratioSummary(filter))
        bindRow(binding.rowResolution, R.string.search_filter_v3_row_resolution, resolutionSummary(filter))
        if (isNovel) {
            bindRow(binding.rowBodyLength, R.string.search_filter_v3_row_body_length, bodyLengthSummary(filter))
        }

        bindRow(binding.rowOther, R.string.search_filter_v3_row_other, otherSummary(filter))
    }

    private fun bindRow(row: CellSearchFilterRowBinding, titleRes: Int, value: String) {
        row.rowTitle.setText(titleRes)
        row.rowValue.text = value
    }

    private fun triggerRefresh() {
        val now = System.currentTimeMillis()
        if (isNovel) searchViewModel.triggerSearchNovelEvent(now)
        else searchViewModel.triggerSearchIllustMangaEvent(now)
        // sort 可能变成了 radio 没有的项（会员的男/女性向人气）——同步 radio 索引（仅新版用）
        val sortIndex = searchViewModel.sortToRadioIndex(currentFilter().sort)
        if (isNovel) searchViewModel.novelSelectedRadioTabIndex.value = sortIndex
        else searchViewModel.illustSelectedRadioTabIndex.value = sortIndex
    }

    // ──────────────────────────────────────────────────────────────────
    // Value summaries
    // ──────────────────────────────────────────────────────────────────

    private fun sortLabel(sort: String): String = getString(when (sort) {
        SortType.POPULAR_PREVIEW     -> R.string.search_filter_v3_sort_popular_preview
        SortType.DATE_DESC           -> R.string.search_filter_v3_sort_date_desc
        SortType.DATE_ASC            -> R.string.search_filter_v3_sort_date_asc
        SortType.POPULAR_DESC        -> R.string.search_filter_v3_sort_popular_desc
        SortType.POPULAR_MALE_DESC   -> R.string.search_filter_v3_sort_popular_male_desc
        SortType.POPULAR_FEMALE_DESC -> R.string.search_filter_v3_sort_popular_female_desc
        else                         -> R.string.search_filter_v3_sort_popular_preview
    })

    private fun searchTargetLabel(target: SearchTarget): String = getString(when (target) {
        SearchTarget.PartialMatchForTags -> R.string.search_filter_v3_target_partial
        SearchTarget.ExactMatchForTags   -> R.string.search_filter_v3_target_exact
        SearchTarget.TitleAndCaption     -> R.string.search_filter_v3_target_title_caption
        SearchTarget.NovelText           -> R.string.search_filter_v3_target_novel_text
        SearchTarget.NovelKeyword        -> R.string.search_filter_v3_target_novel_keyword
    })

    private fun bookmarkLabel(bucket: BookmarkBucket): String =
        if (bucket == BookmarkBucket.None) getString(R.string.search_filter_v3_bookmark_unlimited_summary)
        else getString(R.string.search_filter_v3_bookmark_min, bucket.min)

    private fun keywordBookmarkLabel(bucket: KeywordUsersBucket): String =
        if (bucket == KeywordUsersBucket.None) getString(R.string.search_filter_v3_keyword_bookmark_off_summary)
        else "${bucket.min}users入り"

    private fun durationSummary(filter: SearchFilterV3): String {
        // 三者互斥；bucket 优先；其次 custom 日期；最后「不限」
        filter.durationBucket?.let { bucket ->
            return getString(when (bucket) {
                DurationBucket.Last24Hours   -> R.string.search_filter_v3_duration_24h
                DurationBucket.LastWeek      -> R.string.search_filter_v3_duration_week
                DurationBucket.LastMonth     -> R.string.search_filter_v3_duration_month
                DurationBucket.LastHalfYear  -> R.string.search_filter_v3_duration_half_year
                DurationBucket.LastYear      -> R.string.search_filter_v3_duration_year
            })
        }
        if (filter.startDate != null || filter.endDate != null) {
            return (filter.startDate ?: "—") + " → " + (filter.endDate ?: "—")
        }
        return getString(R.string.search_filter_v3_duration_all)
    }

    private fun genreSummary(filter: SearchFilterV3): String {
        val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
        val match = opts.firstOrNull { it.id == filter.genre }
        return match?.label ?: getString(R.string.search_filter_v3_genre_all_summary)
    }

    private fun langSummary(filter: SearchFilterV3): String {
        if (filter.lang == null) return getString(R.string.search_filter_v3_lang_all_summary)
        val opts = run {
            val resp = searchViewModel.searchOptions.value
            if (isNovel) resp?.novel?.lang?.options.orEmpty()
            else resp?.illust?.lang?.options.orEmpty()
        }
        return opts.firstOrNull { it.code == filter.lang }?.name ?: filter.lang!!
    }

    private fun ratioLabel(pattern: RatioPattern?): String = getString(when (pattern) {
        null                    -> R.string.search_filter_v3_ratio_all
        RatioPattern.Landscape  -> R.string.search_filter_v3_ratio_landscape
        RatioPattern.Portrait   -> R.string.search_filter_v3_ratio_portrait
        RatioPattern.Square     -> R.string.search_filter_v3_ratio_square
    })

    private fun ratioSummary(filter: SearchFilterV3): String = ratioLabel(filter.ratioPattern)

    private fun resolutionLabel(bucket: ResolutionBucket?): String = getString(when (bucket) {
        null                              -> R.string.search_filter_v3_resolution_all
        ResolutionBucket.Above3000        -> R.string.search_filter_v3_resolution_above_3000
        ResolutionBucket.Between1000And2999 -> R.string.search_filter_v3_resolution_1000_2999
        ResolutionBucket.Below1000        -> R.string.search_filter_v3_resolution_below_1000
    })

    private fun resolutionSummary(filter: SearchFilterV3): String =
        resolutionLabel(filter.resolutionBucket)

    private fun contentTypeLabel(type: IllustContentType): String = getString(when (type) {
        IllustContentType.IllustAndMangaAndUgoira -> R.string.search_filter_v3_content_type_illust_and_manga_and_ugoira
        IllustContentType.IllustAndUgoira         -> R.string.search_filter_v3_content_type_illust_and_ugoira
        IllustContentType.Illust                  -> R.string.search_filter_v3_content_type_illust
        IllustContentType.Ugoira                  -> R.string.search_filter_v3_content_type_ugoira
        IllustContentType.Manga                   -> R.string.search_filter_v3_content_type_manga
    })

    // ── 正文长度 ──────────────────────────────────────────────────────────

    private fun charBucketLabel(bucket: CharLengthBucket): String = getString(when (bucket) {
        CharLengthBucket.Micro  -> R.string.search_filter_v3_body_length_micro
        CharLengthBucket.Short  -> R.string.search_filter_v3_body_length_short
        CharLengthBucket.Medium -> R.string.search_filter_v3_body_length_medium
        CharLengthBucket.Long   -> R.string.search_filter_v3_body_length_long
    })

    private fun wordBucketLabel(bucket: WordLengthBucket): String = getString(when (bucket) {
        WordLengthBucket.Below5000  -> R.string.search_filter_v3_word_count_below_5000
        WordLengthBucket.From5000   -> R.string.search_filter_v3_word_count_5000_19999
        WordLengthBucket.From20000  -> R.string.search_filter_v3_word_count_20000_79999
        WordLengthBucket.Above80000 -> R.string.search_filter_v3_word_count_above_80000
    })

    private fun readingTimeBucketLabel(bucket: ReadingTimeBucket): String = getString(when (bucket) {
        ReadingTimeBucket.Under10    -> R.string.search_filter_v3_reading_time_under_10
        ReadingTimeBucket.From10To59 -> R.string.search_filter_v3_reading_time_10_59
        ReadingTimeBucket.From60To179-> R.string.search_filter_v3_reading_time_60_179
        ReadingTimeBucket.Above180   -> R.string.search_filter_v3_reading_time_above_180
    })

    /** picker 入口 summary —— 命中预设档显档名，自定义显数字区间，没设显「不限」。 */
    private fun bodyLengthSummary(filter: SearchFilterV3): String {
        val spec = filter.bodyLength ?: return getString(R.string.search_filter_v3_body_length_all)
        return when (spec.unit) {
            BodyLengthUnit.Char -> {
                val bucket = charLengthList.firstOrNull { it.min == spec.min && it.max == spec.max }
                bucket?.let(::charBucketLabel)
                    ?: getString(R.string.search_filter_v3_body_length_custom_char_summary,
                        rangeText(spec.min, spec.max))
            }
            BodyLengthUnit.Word -> {
                val bucket = wordLengthList.firstOrNull { it.min == spec.min && it.max == spec.max }
                bucket?.let(::wordBucketLabel)
                    ?: getString(R.string.search_filter_v3_body_length_custom_word_summary,
                        rangeText(spec.min, spec.max))
            }
            BodyLengthUnit.ReadingTime -> {
                val bucket = readingTimeBucketList.firstOrNull { it.min == spec.min && it.max == spec.max }
                bucket?.let(::readingTimeBucketLabel)
                    ?: getString(R.string.search_filter_v3_reading_time_custom_summary,
                        rangeText(spec.min, spec.max))
            }
        }
    }

    private fun rangeText(min: Int?, max: Int?): String = when {
        min != null && max != null -> "$min–$max"
        min != null -> "≥$min"
        max != null -> "≤$max"
        else -> "—"
    }

    private fun otherSummary(filter: SearchFilterV3): String {
        val flags = mutableListOf<String>()
        when (filter.aiMode) {
            AiMode.ExcludeAi -> flags += getString(R.string.search_filter_v3_other_summary_no_ai)
            AiMode.OnlyAi    -> flags += getString(R.string.search_filter_v3_other_summary_only_ai)
            AiMode.All -> Unit
        }
        when (filter.r18Mode) {
            R18Mode.SafeOnly -> flags += getString(R.string.search_filter_v3_r18_safe)
            R18Mode.R18Only  -> flags += getString(R.string.search_filter_v3_r18_only)
            R18Mode.All -> Unit
        }
        // illust 专属:制图工具(也搬进了「其他条件」sheet);非「不限」就上 summary
        if (!isNovel) filter.tool?.let { flags += it }
        // novel 专属 2 个 switch —— 也在「其他条件」sheet 里设置；开了就上 summary
        if (isNovel && filter.isOriginalOnly) {
            flags += getString(R.string.search_filter_v3_row_original_only)
        }
        if (isNovel && filter.isReplaceableOnly) {
            flags += getString(R.string.search_filter_v3_row_replaceable_only)
        }
        return if (flags.isEmpty()) getString(R.string.search_filter_v3_other_summary_none)
        else flags.joinToString(" · ")
    }

    // ──────────────────────────────────────────────────────────────────
    // Picker show helpers —— 行点击后呼出
    // ──────────────────────────────────────────────────────────────────

    private fun showSimplePicker(
        requestKey: String,
        title: String,
        labels: List<String>,
        selected: Int,
    ) {
        SimplePickerSheet.newInstance(requestKey, title, labels, selected)
            .show(childFragmentManager, requestKey)
    }

    private fun showTargetPicker() {
        showSimplePicker(
            REQUEST_TARGET,
            getString(R.string.search_filter_v3_row_target),
            targetList.map(::searchTargetLabel),
            targetList.indexOf(currentFilter().searchTarget).coerceAtLeast(0),
        )
    }

    private fun showSortPicker() {
        showSimplePicker(
            REQUEST_SORT,
            getString(R.string.search_filter_v3_row_sort),
            sortList.map(::sortLabel),
            sortList.indexOf(currentFilter().sort).coerceAtLeast(0),
        )
    }

    private fun showBookmarkPicker() {
        showSimplePicker(
            REQUEST_BOOKMARK,
            getString(R.string.search_filter_v3_row_bookmark),
            bookmarkList.map(::bookmarkLabel),
            bookmarkList.indexOf(currentFilter().bookmarkBucket).coerceAtLeast(0),
        )
    }

    private fun showKeywordBookmarkPicker() {
        showSimplePicker(
            REQUEST_KEYWORD_BOOKMARK,
            getString(R.string.search_filter_v3_row_keyword_bookmark),
            keywordUsersList.map(::keywordBookmarkLabel),
            keywordUsersList.indexOf(currentFilter().keywordUsersBucket).coerceAtLeast(0),
        )
    }

    private fun showGenrePicker() {
        val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
        if (opts.isEmpty()) { ensureSearchOptionsLoaded(); return }
        val labels = listOf(getString(R.string.search_filter_v3_genre_all_summary)) + opts.map { it.label }
        val cur = currentFilter().genre
        val selected = if (cur == null) 0 else opts.indexOfFirst { it.id == cur }.let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_GENRE, getString(R.string.search_filter_v3_row_genre), labels, selected)
    }

    private fun showLangPicker() {
        val opts = if (isNovel) searchViewModel.searchOptions.value?.novel?.lang?.options.orEmpty()
                   else searchViewModel.searchOptions.value?.illust?.lang?.options.orEmpty()
        if (opts.isEmpty()) { ensureSearchOptionsLoaded(); return }
        val labels = listOf(getString(R.string.search_filter_v3_lang_all_summary)) + opts.map { it.name }
        val cur = currentFilter().lang
        val selected = if (cur == null) 0 else opts.indexOfFirst { it.code == cur }.let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_LANG, getString(R.string.search_filter_v3_row_lang), labels, selected)
    }

    private fun showRatioPicker() {
        val labels = listOf(getString(R.string.search_filter_v3_ratio_all)) +
            ratioList.map { ratioLabel(it) }
        val cur = currentFilter().ratioPattern
        val selected = if (cur == null) 0 else ratioList.indexOf(cur).let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_RATIO, getString(R.string.search_filter_v3_row_ratio), labels, selected)
    }

    private fun showContentTypePicker() {
        showSimplePicker(
            REQUEST_CONTENT_TYPE,
            getString(R.string.search_filter_v3_row_content_type),
            contentTypeList.map(::contentTypeLabel),
            contentTypeList.indexOf(currentFilter().contentType).coerceAtLeast(0),
        )
    }

    private fun showResolutionPicker() {
        val labels = listOf(getString(R.string.search_filter_v3_resolution_all)) +
            resolutionList.map { resolutionLabel(it) }
        val cur = currentFilter().resolutionBucket
        val selected = if (cur == null) 0
            else resolutionList.indexOf(cur).let { if (it < 0) 0 else it + 1 }
        showSimplePicker(
            REQUEST_RESOLUTION,
            getString(R.string.search_filter_v3_row_resolution),
            labels,
            selected,
        )
    }

    // ── 正文长度 picker：一个 sheet 里展三段（文字数 / 单词数 / 阅读预计用时），分段标题 ──
    //
    // labels 顺序:
    //   0  = 不限
    //   1  = [HEADER] 文字数
    //   2..5 = CharLengthBucket 4 档
    //   6  = 指定文字数 (P)
    //   7  = [HEADER] 单词数
    //   8..11 = WordLengthBucket 4 档
    //   12 = 指定单词数 (P)
    //   13 = [HEADER] 阅读预计用时
    //   14..17 = ReadingTimeBucket 4 档
    //   18 = 指定阅读预计用时 (P)
    //
    // 索引区间是连续编码，三种 unit 共用一个 picker；handleBodyLengthPick 按 idx 区间分派。
    private fun bodyLengthLabels(): List<String> {
        val custom = getString(R.string.search_filter_v3_premium_marker)  // 「(P)」
        return buildList {
            add(getString(R.string.search_filter_v3_body_length_all))
            add(getString(R.string.search_filter_v3_body_length_section_char))    // header
            addAll(charLengthList.map(::charBucketLabel))
            add(getString(R.string.search_filter_v3_body_length_custom_char) + " " + custom)
            add(getString(R.string.search_filter_v3_body_length_section_word))    // header
            addAll(wordLengthList.map(::wordBucketLabel))
            add(getString(R.string.search_filter_v3_body_length_custom_word) + " " + custom)
            add(getString(R.string.search_filter_v3_body_length_section_time))    // header
            addAll(readingTimeBucketList.map(::readingTimeBucketLabel))
            add(getString(R.string.search_filter_v3_reading_time_custom) + " " + custom)
        }
    }

    private fun bodyLengthSelectedIdx(spec: BodyLengthSpec?): Int {
        if (spec == null) return 0
        return when (spec.unit) {
            BodyLengthUnit.Char -> {
                val bIdx = charLengthList.indexOfFirst { it.min == spec.min && it.max == spec.max }
                if (bIdx >= 0) 2 + bIdx else 6   // 命中预设 → bucket idx；命中不到 → 指定文字数
            }
            BodyLengthUnit.Word -> {
                val bIdx = wordLengthList.indexOfFirst { it.min == spec.min && it.max == spec.max }
                if (bIdx >= 0) 8 + bIdx else 12
            }
            BodyLengthUnit.ReadingTime -> {
                val bIdx = readingTimeBucketList.indexOfFirst { it.min == spec.min && it.max == spec.max }
                if (bIdx >= 0) 14 + bIdx else 18
            }
        }
    }

    private fun showBodyLengthPicker() {
        // 单词数适用语言提示由 /v1/search/options 的 novel.word_count_supported_languages 字段提供
        // （服务端按 app-accept-language 已经本地化好）。没拉到就不显示，picker 仍可用。
        val footer = searchViewModel.searchOptions.value?.novel?.wordCountSupportedLanguages.orEmpty()
        SimplePickerSheet.newInstance(
            REQUEST_BODY_LENGTH,
            getString(R.string.search_filter_v3_row_body_length),
            bodyLengthLabels(),
            bodyLengthSelectedIdx(currentFilter().bodyLength),
            headerIndices = intArrayOf(1, 7, 13),
            footerHint = footer,
        ).show(childFragmentManager, REQUEST_BODY_LENGTH)
    }

    private fun handleBodyLengthPick(idx: Int) {
        when (idx) {
            0 -> updateFilter { it.copy(bodyLength = null) }
            in 2..5 -> {
                val bucket = charLengthList[idx - 2]
                updateFilter { it.copy(bodyLength = BodyLengthSpec(BodyLengthUnit.Char, bucket.min, bucket.max)) }
            }
            6 -> showBodyLengthCustomInput(BodyLengthUnit.Char)
            in 8..11 -> {
                val bucket = wordLengthList[idx - 8]
                updateFilter { it.copy(bodyLength = BodyLengthSpec(BodyLengthUnit.Word, bucket.min, bucket.max)) }
            }
            12 -> showBodyLengthCustomInput(BodyLengthUnit.Word)
            in 14..17 -> {
                val bucket = readingTimeBucketList[idx - 14]
                updateFilter { it.copy(bodyLength = BodyLengthSpec(BodyLengthUnit.ReadingTime, bucket.min, bucket.max)) }
            }
            18 -> showBodyLengthCustomInput(BodyLengthUnit.ReadingTime)
        }
    }

    private fun showBodyLengthCustomInput(unit: BodyLengthUnit) {
        val cur = currentFilter().bodyLength?.takeIf { it.unit == unit }
        val titleRes: Int
        val unitRes: Int
        val requestKey: String
        when (unit) {
            BodyLengthUnit.Char -> {
                titleRes = R.string.search_filter_v3_body_length_custom_char
                unitRes = R.string.search_filter_v3_body_length_unit_char
                requestKey = REQUEST_BODY_LENGTH_CUSTOM_CHAR
            }
            BodyLengthUnit.Word -> {
                titleRes = R.string.search_filter_v3_body_length_custom_word
                unitRes = R.string.search_filter_v3_body_length_unit_word
                requestKey = REQUEST_BODY_LENGTH_CUSTOM_WORD
            }
            BodyLengthUnit.ReadingTime -> {
                titleRes = R.string.search_filter_v3_reading_time_custom
                unitRes = R.string.search_filter_v3_reading_time_unit
                requestKey = REQUEST_BODY_LENGTH_CUSTOM_TIME
            }
        }
        NumberRangeInputSheet.newInstance(
            requestKey,
            getString(titleRes),
            getString(unitRes),
            cur?.min,
            cur?.max,
        ).show(childFragmentManager, requestKey)
    }

    private fun showDurationPicker() {
        DurationPickerSheet.newInstance(REQUEST_DURATION, currentFilter())
            .show(childFragmentManager, REQUEST_DURATION)
    }

    private fun showDateRangePicker() {
        DateRangePickerSheet.newInstance(REQUEST_DURATION_DATES, currentFilter())
            .show(childFragmentManager, REQUEST_DURATION_DATES)
    }

    private fun showOtherSheet() {
        // 工具候选不再 snapshot 透传;OtherFilterSheet 直接从 parent VM 现读,
        // /v1/search/options 异步加载完一到位下次点击就生效
        OtherFilterSheet.newInstance(REQUEST_OTHER, currentFilter(), isNovel = isNovel)
            .show(childFragmentManager, REQUEST_OTHER)
    }

    // ──────────────────────────────────────────────────────────────────
    // /v1/search/options 拉取
    // ──────────────────────────────────────────────────────────────────

    /** internal so child sheets can re-trigger the load if they find options still empty. */
    internal fun ensureSearchOptionsLoaded() {
        if (searchViewModel.searchOptions.value != null) return
        // /v1/search/options 实测响应与 word 无关，但服务端要求该参数非空。
        // 直接用用户的 keyword；空就传一个无害占位。
        val keyword = searchViewModel.tagList.value
            ?.firstOrNull()?.name?.takeIf { !it.isNullOrEmpty() }
            ?: PLACEHOLDER_KEYWORD
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = Client.appApi.searchOptions(word = keyword)
                searchViewModel.searchOptions.value = resp
                renderRows()
            } catch (ex: Exception) {
                Timber.tag(TAG).w(ex, "searchOptions failed")
            }
        }
    }

    companion object {
        private const val TAG = "SearchFilterV3"
        private const val ARG_OBJECT_TYPE = "objectType"
        private const val ARG_LEGACY = "legacy"

        // 拉 /v1/search/options 用的占位关键词。pixiv API 要求 word 非空但实际响应与之无关。
        private const val PLACEHOLDER_KEYWORD = "art"

        // FragmentResult request keys —— 在 onViewCreated 里登记 listener，picker setFragmentResult 命中。
        private const val REQUEST_TARGET   = "v3_filter_target"
        private const val REQUEST_SORT     = "v3_filter_sort"
        private const val REQUEST_BOOKMARK = "v3_filter_bookmark"
        private const val REQUEST_KEYWORD_BOOKMARK = "v3_filter_keyword_bookmark"
        private const val REQUEST_GENRE    = "v3_filter_genre"
        private const val REQUEST_LANG     = "v3_filter_lang"
        private const val REQUEST_RATIO    = "v3_filter_ratio"
        private const val REQUEST_RESOLUTION = "v3_filter_resolution"
        private const val REQUEST_CONTENT_TYPE = "v3_filter_content_type"
        private const val REQUEST_BODY_LENGTH = "v3_filter_body_length"
        private const val REQUEST_BODY_LENGTH_CUSTOM_CHAR = "v3_filter_body_length_custom_char"
        private const val REQUEST_BODY_LENGTH_CUSTOM_WORD = "v3_filter_body_length_custom_word"
        private const val REQUEST_BODY_LENGTH_CUSTOM_TIME = "v3_filter_body_length_custom_time"
        private const val REQUEST_DURATION = "v3_filter_duration"
        private const val REQUEST_DURATION_DATES = "v3_filter_duration_dates"
        private const val REQUEST_OTHER    = "v3_filter_other"

        @JvmStatic
        @JvmOverloads
        fun newInstance(objectType: String, legacy: Boolean = false): SearchFilterV3BottomSheet =
            SearchFilterV3BottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_OBJECT_TYPE, objectType)
                    putBoolean(ARG_LEGACY, legacy)
                }
            }
    }
}
