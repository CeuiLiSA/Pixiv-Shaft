package ceui.pixiv.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.isEmpty
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.databinding.SheetBookmarkFilterBinding
import ceui.pixiv.db.mirror.AgeFilter
import ceui.pixiv.db.mirror.AiFilter
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorMapper
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.BookmarkYearFacet
import ceui.pixiv.db.mirror.PageFilter
import ceui.pixiv.db.mirror.ValidityFilter
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.screenHeight
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 本地库（收藏 / 关注）的「筛选与排序」面板。V3 + MD3-E，零件见 [LibraryFilterViews]。
 *
 * ## 版式
 *
 * 照规范的 Sheet 配方：标题 → 当前状态（书架总数 · 开了几项筛选）→ 条件 → 主操作。
 * 条件按「排序 / 作品 / 人气与时间 / 标签 / 作者」分进几张 22dp 分组卡，卡内一行一个维度；
 * 互斥的档位是连通选择组，可叠加的是带勾的胶囊，开关是开关行 —— 控件形状本身就说明了
 * 「只能选一个 / 可以选几个 / 开或关」，不用再靠文字解释。
 *
 * 哪几节出现由书架的 [LibraryProfile] 决定：关注书架里一行是一个人，没有分级、人气、作者
 * 这些作品维度，只留排序、最近投稿年份和（取自最近作品的）标签。
 *
 * ## 交互取舍
 *
 * - **即时生效，没有「取消」**：每点一下就写进 VM、命中数当场变，底部主操作只是
 *   「看结果去」。筛选是探索行为，不是填表单。要退回原样有标题行的「清空」。
 * - **标签点一下是「要」，长按是「不要」**：排除是低频但关键的动作，给它独立按钮会让每个
 *   标签变成两个控件；藏在长按里，触发时用危险色明确回显。
 * - **标签云是共现的**：列出来的永远是「在当前结果里还剩多少件」，一路往下点绝不会点出 0 条。
 */
class BookmarkFilterSheet : BottomSheetDialogFragment() {

    /** 宿主契约：条件变了让列表重刷。 */
    interface Host {
        fun onBookmarkFilterChanged()
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    /** 与宿主页共用同一个 VM 实例：条件、facet、计数全在那里，sheet 自己不存状态。 */
    private val viewModel: BookmarkLibraryViewModel by viewModels({ requireParentFragment() })

    private var _binding: SheetBookmarkFilterBinding? = null
    private val binding get() = _binding!!

    private var views: LibraryFilterViews? = null

    private val profile: LibraryProfile get() = LibraryProfile.of(viewModel.shelf.contentType)

    /** 每次条件变更后把所有控件的选中态刷一遍（控件数量在百级，一次全刷远比精确定位便宜）。 */
    private val refreshers = mutableListOf<() -> Unit>()

    /** 标签搜索框里的当前文本（只过滤已经算好的标签云，不打库）。 */
    private var tagQuery: String = ""

    /**
     * 见过的标签名 → 展示名/译名。**被排除的标签不会出现在 facet 结果里**（facet 算的是
     * 当前结果里还剩什么），没有这份缓存，用户一旦长按排除某个标签就再也看不到那个胶囊、
     * 也就没法取消排除。
     */
    private val knownTagLabels = HashMap<String, Pair<String, String>>()

    private var tagFlow: FlexboxLayout? = null
    private var authorFlow: FlexboxLayout? = null

    /** 标签搜索框。「清空」要连它一起清 —— 见 [onViewCreated] 里 reset 的注释。 */
    private var tagSearchInput: EditText? = null

    /** 上次建各节时用的年份列表。只有它变了才值得推倒重建（见 [onViewCreated] 的收集器）。 */
    private var builtYears: List<BookmarkYearFacet> = emptyList()

    /** 年份那一组的单选控件。年份集合没变、只是件数变了时就地改文案，不推倒整张面板。 */
    private var yearGroup: LibraryFilterViews.SegmentedGroup? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetBookmarkFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 进程被杀后系统会连着子 FragmentManager 一起恢复这张 sheet，而 VM 是全新的：
        // 宿主的 onViewCreated（bind 的地方）**通常**先于本方法，但这是 FragmentManager 的
        // 内部状态推进顺序，不是契约。恢复出来的空 sheet 本来也没有价值，直接关掉最干净。
        if (!viewModel.bound || viewModel.mirrorState.value?.isFirstSyncDone != true) {
            Timber.tag(TAG).w("书架尚未补齐，关闭恢复出来的筛选面板")
            dismissAllowingStateLoss()
            return
        }
        val parts = LibraryFilterViews(requireContext()).also { views = it }
        parts.styleResetAction(binding.resetButton)
        parts.stylePrimaryAction(binding.applyButton)
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.sheetTitle, true)

        buildSections()
        binding.resetButton.setOnClickListener {
            if (viewModel.clearConditions()) {
                // 两个搜索框都要跟着空掉：条件已经清了，框里却还留着字，界面就在说谎
                //（而且用户接着敲一个字，整串旧关键词会连着新字一起被重新应用）。
                // 宿主那个搜索框由宿主自己同步（见 BookmarkLibraryUi.renderChips）。
                tagQuery = ""
                tagSearchInput?.setText("")
                notifyHost()
            }
            rebuildTagChips()
            refreshAll()
        }
        binding.applyButton.setOnClickListener { dismissAllowingStateLoss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.mirrorState.collectLatest {
                        if (it?.isFirstSyncDone != true) dismissAllowingStateLoss()
                    }
                }
                launch { viewModel.resultCount.collectLatest { updateApplyText(it) } }
                launch { viewModel.shelfStats.collectLatest { renderHeader() } }
                // facet 是异步算出来的：标签/作者两节先出骨架、算完再填，不阻塞面板打开
                launch { viewModel.tagFacets.collectLatest { rebuildTagChips() } }
                launch { viewModel.authorFacets.collectLatest { rebuildAuthorChips() } }
                // 年份分区要等 facet 算完才建得出来。**只在年份集合真的变了时才重建**：
                // StateFlow 订阅时会立刻重放当前值，无条件重建等于一开面板就把刚建好的
                // 各节全部推倒重来；后台补进新数据时同理，会把用户正在调的面板
                // 连滚动位置带标签搜索框一起清掉。只是某年件数变了（增量维护补进一件收藏、
                // 关注库刷新了某人的最近投稿）就只改那几个文案。
                launch {
                    viewModel.yearFacets.collectLatest { years ->
                        when {
                            years == builtYears -> Unit
                            yearGroup != null && years.map { it.year } == builtYears.map { it.year } ->
                                relabelYears(years)
                            else -> buildSections()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        makeSheetTransparentAndFillNavBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshers.clear()
        tagFlow = null
        authorFlow = null
        tagSearchInput = null
        yearGroup = null
        views = null
        _binding = null
    }

    // ─────────────────────────── 各组 ───────────────────────────

    private fun buildSections() {
        val parts = views ?: return
        val container = binding.sectionsContainer
        container.removeAllViews()
        refreshers.clear()
        tagFlow = null
        authorFlow = null
        tagSearchInput = null
        yearGroup = null
        builtYears = viewModel.yearFacets.value
        val profile = profile

        group(getString(R.string.bookmark_filter_section_sort)) { card ->
            singleChoice(
                card,
                title = null,
                options = profile.sorts.map { it to getString(profile.sortLabel(it)) },
                selected = { it.sort },
            ) { filter, value -> filter.copy(sort = value, randomSeed = freshSeedIfRandom(filter, value)) }
        }

        if (profile.hasWorkFilters) {
            group(getString(R.string.bookmark_filter_group_works)) { card -> buildWorkRows(card, profile) }
        }

        val timeGroup = if (profile.hasWorkFilters) {
            R.string.bookmark_filter_group_popularity_time
        } else {
            R.string.bookmark_filter_group_time
        }
        group(getString(timeGroup)) { card ->
            if (profile.hasWorkFilters) {
                singleChoice(
                    card,
                    title = getString(R.string.bookmark_filter_section_popularity),
                    options = POPULARITY_STEPS.map { step ->
                        step to if (step == null) {
                            getString(R.string.bookmark_filter_any)
                        } else {
                            getString(R.string.bookmark_filter_popularity_min, formatCount(step))
                        }
                    },
                    selected = { it.minBookmarks },
                ) { filter, value -> filter.copy(minBookmarks = value) }
            }
            val years = builtYears
            if (years.isNotEmpty()) {
                yearGroup = singleChoice(
                    card,
                    title = getString(profile.yearSection),
                    options = buildList {
                        add(null to getString(R.string.bookmark_filter_any))
                        years.forEach { facet -> add(facet.year to yearLabel(facet)) }
                    },
                    selected = { filter -> filter.createdFromMs?.let(::yearOf) },
                ) { filter, year ->
                    if (year == null) {
                        filter.copy(createdFromMs = null, createdToMs = null)
                    } else {
                        filter.copy(createdFromMs = yearStartMs(year), createdToMs = yearStartMs(year + 1) - 1)
                    }
                }
            }
            if (profile.hasWorkFilters) {
                val (row, switch) = parts.switchRow(getString(R.string.bookmark_filter_series_only)) { checked ->
                    applyChange { it.copy(seriesOnly = checked) }
                    refreshAll()
                }
                parts.row(card, title = null, control = row)
                refreshers += { switch.isChecked = viewModel.filter.value.seriesOnly }
            }
        }

        group(getString(R.string.bookmark_filter_section_tags)) { card -> buildTagRows(card, profile) }

        if (profile.hasWorkFilters) {
            group(getString(R.string.bookmark_filter_section_author)) { card ->
                authorFlow = parts.newFlow().also { parts.row(card, title = null, control = it) }
                rebuildAuthorChips()
            }
        }

        refreshAll()
    }

    /** 作品维度：类型 / 画幅 / 页数（插画）或字数（小说），以及分级 / AI / 作品状态。 */
    private fun buildWorkRows(card: LinearLayout, profile: LibraryProfile) {
        if (profile.isIllust) {
            multiChoice(
                card,
                title = getString(R.string.bookmark_filter_section_type),
                options = listOf(
                    "illust" to getString(R.string.bookmark_filter_type_illust),
                    "manga" to getString(R.string.bookmark_filter_type_manga),
                    "ugoira" to getString(R.string.bookmark_filter_type_ugoira),
                ),
                selected = { it.workTypes.toSet() },
            ) { filter, values -> filter.copy(workTypes = values.toList()) }

            multiChoice(
                card,
                title = getString(R.string.bookmark_filter_section_shape),
                options = listOf(
                    BookmarkMirrorMapper.ORIENTATION_LANDSCAPE to getString(R.string.bookmark_filter_shape_landscape),
                    BookmarkMirrorMapper.ORIENTATION_PORTRAIT to getString(R.string.bookmark_filter_shape_portrait),
                    BookmarkMirrorMapper.ORIENTATION_SQUARE to getString(R.string.bookmark_filter_shape_square),
                ),
                selected = { it.orientations.toSet() },
            ) { filter, values -> filter.copy(orientations = values.toList()) }

            singleChoice(
                card,
                title = getString(R.string.bookmark_filter_section_pages),
                options = listOf(
                    PageFilter.ANY to getString(R.string.bookmark_filter_any),
                    PageFilter.SINGLE_PAGE to getString(R.string.bookmark_filter_pages_single),
                    PageFilter.MULTI_PAGE to getString(R.string.bookmark_filter_pages_multi),
                ),
                selected = { it.pages },
            ) { filter, value -> filter.copy(pages = value) }
        }

        if (profile.isNovel) {
            // 小说侧「人气」之外最实用的那一维：想找长篇 / 想找一口气看完的短篇。
            singleChoice(
                card,
                title = getString(R.string.bookmark_filter_section_length),
                options = LENGTH_STEPS.map { step ->
                    step to if (step == null) {
                        getString(R.string.bookmark_filter_any)
                    } else {
                        getString(R.string.bookmark_filter_length_min, formatCount(step))
                    }
                },
                selected = { it.minTextLength },
            ) { filter, value -> filter.copy(minTextLength = value) }
        }

        singleChoice(
            card,
            title = getString(R.string.bookmark_filter_section_age),
            options = listOf(
                AgeFilter.ANY to getString(R.string.bookmark_filter_any),
                AgeFilter.ALL_AGES to getString(R.string.bookmark_filter_age_all),
                AgeFilter.R18 to getString(R.string.bookmark_filter_age_r18),
                AgeFilter.R18G to getString(R.string.bookmark_filter_age_r18g),
            ),
            selected = { it.age },
        ) { filter, value -> filter.copy(age = value) }

        singleChoice(
            card,
            title = getString(R.string.bookmark_filter_section_ai),
            options = listOf(
                AiFilter.ANY to getString(R.string.bookmark_filter_any),
                AiFilter.EXCLUDE_AI to getString(R.string.bookmark_filter_ai_exclude),
                AiFilter.ONLY_AI to getString(R.string.bookmark_filter_ai_only),
            ),
            selected = { it.ai },
        ) { filter, value -> filter.copy(ai = value) }

        singleChoice(
            card,
            title = getString(R.string.bookmark_filter_section_state),
            options = listOf(
                ValidityFilter.ANY to getString(R.string.bookmark_filter_any),
                ValidityFilter.VALID_ONLY to getString(R.string.bookmark_filter_state_valid),
                // 「只看失效」是这张表白拿的能力：失效收藏平时混在几千件里根本找不出来，
                // 单独筛出来才谈得上清理。
                ValidityFilter.INVALID_ONLY to getString(R.string.bookmark_filter_state_invalid),
            ),
            selected = { it.validity },
        ) { filter, value -> filter.copy(validity = value) }
    }

    private fun yearLabel(facet: BookmarkYearFacet): String =
        getString(R.string.bookmark_filter_year_item, facet.year, facet.hitCount)

    /** 年份集合与已建的一致，只刷新每一项的件数（第 0 项是「不限」）。 */
    private fun relabelYears(years: List<BookmarkYearFacet>) {
        builtYears = years
        yearGroup?.setLabels(listOf(getString(R.string.bookmark_filter_any)) + years.map(::yearLabel))
    }

    private fun freshSeedIfRandom(filter: BookmarkFilter, value: BookmarkSort): Long =
        if (value.isRandom) System.currentTimeMillis() else filter.randomSeed

    /** 标签组：规则说明 + 搜索框 +（选了两个以上才出现的）匹配方式 + 标签云。 */
    private fun buildTagRows(card: LinearLayout, profile: LibraryProfile) {
        val parts = views ?: return
        val search = parts.searchField(getString(R.string.bookmark_filter_tag_search_hint)).apply {
            setText(tagQuery)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    tagQuery = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    rebuildTagChips()
                }
            })
        }
        tagSearchInput = search
        parts.row(card, title = null, hint = getString(profile.tagHint), control = search)

        // 「同时满足 / 任一满足」只在选了两个以上标签时才有意义，之前出现只是噪音。
        val modes = listOf(true, false)
        val modeGroup = parts.segmentedGroup(
            listOf(
                getString(R.string.bookmark_filter_tag_mode_all),
                getString(R.string.bookmark_filter_tag_mode_any),
            ),
        ) { index ->
            applyChange { it.copy(tagMatchAll = modes[index]) }
            refreshAll()
        }
        val modeRow = parts.row(
            card,
            title = getString(R.string.bookmark_filter_tag_mode_label),
            control = modeGroup.view,
            divider = false,
        )
        refreshers += {
            val filter = viewModel.filter.value
            modeRow.visibility = if (filter.tagNames.size > 1) View.VISIBLE else View.GONE
            modeGroup.select(modes.indexOf(filter.tagMatchAll))
        }

        tagFlow = parts.newFlow().also { parts.row(card, title = null, control = it, divider = false) }
        rebuildTagChips()
    }

    private fun rebuildTagChips() {
        val flow = tagFlow ?: return
        val parts = views ?: return
        flow.removeAllViews()
        val filter = viewModel.filter.value
        val facets = viewModel.tagFacets.value
        facets.forEach { knownTagLabels[it.tagName] = it.displayName to it.translatedName }

        // 排除掉的标签不在 facet 里（见 knownTagLabels），得自己补一份「幽灵胶囊」出来，
        // 否则排除就是个单向操作，取消不掉。
        val entries = buildList {
            filter.excludedTagNames.forEach { name ->
                val (display, translated) = knownTagLabels[name] ?: (name to "")
                add(TagChipEntry(name, display, translated, hitCount = null))
            }
            facets.forEach { facet ->
                add(TagChipEntry(facet.tagName, facet.displayName, facet.translatedName, facet.hitCount))
            }
        }
            // 已选中的钉在最前：标签云会随着每次下钻整体重排，选中的胶囊一旦被挤到
            // 几十个之后，用户就找不到自己刚点了什么、也退不回去了。
            .sortedByDescending { it.tagName in filter.excludedTagNames || it.tagName in filter.tagNames }
            .filter { entry ->
                tagQuery.isEmpty() ||
                    entry.tagName.contains(tagQuery) ||
                    entry.translatedName.lowercase(Locale.ROOT).contains(tagQuery)
            }

        if (entries.isEmpty()) {
            flow.addView(parts.hint(getString(R.string.bookmark_filter_tag_empty)))
            return
        }
        entries.take(TAG_CHIP_LIMIT).forEach { entry ->
            val included = entry.tagName in filter.tagNames
            val excluded = entry.tagName in filter.excludedTagNames
            val label = buildString {
                append(entry.displayName)
                if (entry.translatedName.isNotEmpty() && entry.translatedName != entry.displayName) {
                    append(" · ").append(entry.translatedName)
                }
            }
            val chip = parts.filterChip(label, entry.hitCount) {
                applyChange { current ->
                    // 点击在「不选 → 包含 → 不选」之间转；排除态点一下直接回到不选
                    when {
                        excluded -> current.copy(excludedTagNames = current.excludedTagNames - entry.tagName)
                        included -> current.copy(tagNames = current.tagNames - entry.tagName)
                        else -> current.copy(tagNames = current.tagNames + entry.tagName)
                    }
                }
                rebuildTagChips()
                refreshAll()
            }
            chip.setOnLongClickListener {
                applyChange { current ->
                    if (excluded) {
                        current.copy(excludedTagNames = current.excludedTagNames - entry.tagName)
                    } else {
                        current.copy(
                            tagNames = current.tagNames - entry.tagName,
                            excludedTagNames = current.excludedTagNames + entry.tagName,
                        )
                    }
                }
                rebuildTagChips()
                refreshAll()
                true
            }
            parts.renderChip(chip, selected = included, excluded = excluded)
            flow.addView(chip)
        }
    }

    /** 标签云里的一枚胶囊。[hitCount] 为 null = 被排除的「幽灵项」，它已经不在结果里了。 */
    private class TagChipEntry(
        val tagName: String,
        val displayName: String,
        val translatedName: String,
        val hitCount: Int?,
    )

    private fun rebuildAuthorChips() {
        val flow = authorFlow ?: return
        val parts = views ?: return
        flow.removeAllViews()
        val filter = viewModel.filter.value
        val facets = viewModel.authorFacets.value
        if (facets.isEmpty()) {
            flow.addView(parts.hint(getString(R.string.bookmark_filter_author_empty)))
            return
        }
        facets.forEach { facet ->
            val selected = facet.authorId in filter.authorIds
            val chip = parts.filterChip(facet.authorName, facet.hitCount) {
                applyChange { current ->
                    current.copy(
                        authorIds = if (selected) current.authorIds - facet.authorId
                        else current.authorIds + facet.authorId
                    )
                }
                rebuildAuthorChips()
                refreshAll()
            }
            parts.renderChip(chip, selected = selected)
            flow.addView(chip)
        }
    }

    // ───────────────────── 声明式的行构造器 ─────────────────────

    /** 一组 = 卡外的组标题 + 一张分组卡。卡里一行都没有（比如关注书架还没有年份）就整组不出。 */
    private fun group(title: String, build: (LinearLayout) -> Unit) {
        val parts = views ?: return
        val container = binding.sectionsContainer
        val card = parts.groupCard()
        build(card)
        if (card.isEmpty()) return
        container.addView(parts.groupHeader(title, first = container.isEmpty()))
        container.addView(card)
    }

    private fun <T> singleChoice(
        card: LinearLayout,
        title: String?,
        options: List<Pair<T, String>>,
        selected: (BookmarkFilter) -> T,
        apply: (BookmarkFilter, T) -> BookmarkFilter,
    ): LibraryFilterViews.SegmentedGroup? {
        val parts = views ?: return null
        val group = parts.segmentedGroup(options.map { it.second }) { index ->
            applyChange { apply(it, options[index].first) }
            refreshAll()
        }
        parts.row(card, title, control = group.view)
        refreshers += {
            val current = selected(viewModel.filter.value)
            group.select(options.indexOfFirst { it.first == current })
        }
        return group
    }

    private fun <T> multiChoice(
        card: LinearLayout,
        title: String,
        options: List<Pair<T, String>>,
        selected: (BookmarkFilter) -> Set<T>,
        apply: (BookmarkFilter, Set<T>) -> BookmarkFilter,
    ) {
        val parts = views ?: return
        val flow = parts.newFlow()
        options.forEach { (value, label) ->
            val chip = parts.filterChip(label) {
                applyChange { current ->
                    val now = selected(current)
                    apply(current, if (value in now) now - value else now + value)
                }
                refreshAll()
            }
            refreshers += { parts.renderChip(chip, value in selected(viewModel.filter.value)) }
            flow.addView(chip)
        }
        parts.row(card, title, control = flow)
    }

    // ─────────────────────────── 零件 ───────────────────────────

    private fun applyChange(transform: (BookmarkFilter) -> BookmarkFilter) {
        if (viewModel.updateFilter(transform)) notifyHost()
    }

    private fun notifyHost() {
        (parentFragment as? Host)?.onBookmarkFilterChanged()
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        renderHeader()
        updateApplyText(viewModel.resultCount.value)
    }

    /** 标题下那行当前状态 + 「清空」的可用性。 */
    private fun renderHeader() {
        val b = _binding ?: return
        val filter = viewModel.filter.value
        val conditions = filter.activeConditionCount
        val active = if (conditions > 0) {
            getString(R.string.bookmark_filter_active_count, conditions)
        } else {
            getString(R.string.bookmark_filter_none_active)
        }
        val total = viewModel.shelfStats.value?.total
        b.summaryText.text = if (total == null) {
            active
        } else {
            "${getString(profile.totalCount, formatCount(total))} · $active"
        }
        b.resetButton.isEnabled = filter.hasAnyCondition
        b.resetButton.alpha = if (filter.hasAnyCondition) 1f else DISABLED_ALPHA
    }

    private fun updateApplyText(count: Int?) {
        _binding?.applyButton?.text = if (count == null) {
            getString(R.string.bookmark_library_filter_apply_pending)
        } else {
            getString(profile.showResults, formatCount(count))
        }
    }

    private fun yearOf(epochMs: Long): Int = Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.YEAR)

    private fun yearStartMs(year: Int): Long = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
    }.timeInMillis

    companion object {
        private const val MAX_HEIGHT_FRACTION = 0.88

        /** 没有可清的条件时「清空」的透明度：看得见在哪，但明确点不了。 */
        private const val DISABLED_ALPHA = 0.38f

        /** 标签云一次最多铺这么多胶囊：再多一屏也看不完，还会把 sheet 撑得滚不到底。 */
        private const val TAG_CHIP_LIMIT = 60

        /** 人气档位。用预设档而不是数字输入框：用户脑子里就是「几千收藏以上」这种量级。 */
        private val POPULARITY_STEPS: List<Int?> = listOf(null, 500, 2_000, 10_000, 30_000)

        /** 小说字数档位。一万字上下大致是「一顿饭能看完」和「要分几次看」的分界。 */
        private val LENGTH_STEPS: List<Int?> = listOf(null, 5_000, 20_000, 50_000, 100_000)

        fun show(host: androidx.fragment.app.Fragment) {
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            BookmarkFilterSheet().show(host.childFragmentManager, TAG)
        }

        private const val TAG = "BookmarkFilterSheet"

        /** 千分位，长列表里的数字扫一眼就能读出量级。 */
        fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
    }
}
