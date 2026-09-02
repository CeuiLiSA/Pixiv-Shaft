package ceui.pixiv.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import ceui.lisa.R
import ceui.lisa.databinding.SheetBookmarkFilterBinding
import ceui.lisa.utils.DensityUtil
import ceui.pixiv.db.mirror.AgeFilter
import ceui.pixiv.db.mirror.AiFilter
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorMapper
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.BookmarkYearFacet
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.PageFilter
import ceui.pixiv.db.mirror.ValidityFilter
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zhy.view.flowlayout.FlowLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 收藏库的「筛选与排序」面板。
 *
 * ## 交互取舍
 *
 * - **即时生效，没有「取消」**：每点一下就写进 VM、命中数当场变，底部 CTA 只是
 *   「看结果去」。筛选是探索行为，不是填表单——先看到结果变化再决定下一步，比
 *   「攒一堆条件再提交、错了从头再来」快得多。要退回原样有标题行的「清空」。
 * - **标签点一下是「要」，长按是「不要」**：排除是低频但关键的动作（想看某个画师
 *   但不想看某个系列），给它一个独立的按钮会让每个标签 chip 变成两个控件；藏在长按里
 *   既不占地方，触发时又用危险色明确回显。
 * - **标签云是共现的**：列出来的标签永远是「在当前结果里还剩多少件」，所以一路往下
 *   点绝不会点出 0 条结果——这正是 facet 检索比自由输入好用的地方。
 *
 * ## 为什么各节是代码生成的
 *
 * 十几个维度写成 XML 就是十几段几乎一样的复制粘贴，加一个维度要动三处。这里用
 * [singleChoiceSection] / [multiChoiceSection] 两个声明式构造器，加维度 = 加一段调用。
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

    /** 每次条件变更后把所有 chip 的选中态刷一遍（chip 数量在百级，一次全刷远比精确定位便宜）。 */
    private val refreshers = mutableListOf<() -> Unit>()

    /** 标签搜索框里的当前文本（只过滤已经算好的标签云，不打库）。 */
    private var tagQuery: String = ""

    /**
     * 见过的标签名 → 展示名/译名。**被排除的标签不会出现在 facet 结果里**（facet 算的是
     * 当前结果里还剩什么，而排除掉的东西按定义已经不在结果里了），没有这份缓存，用户
     * 一旦长按排除某个标签就再也看不到那个 chip、也就没法取消排除。
     */
    private val knownTagLabels = HashMap<String, Pair<String, String>>()

    private var tagFlow: FlowLayout? = null
    private var authorFlow: FlowLayout? = null

    /** 标签搜索框。「清空」要连它一起清 —— 见 [onViewCreated] 里 reset 的注释。 */
    private var tagSearchInput: EditText? = null

    /** 上次建各节时用的年份列表。只有它变了才值得推倒重建（见 [onViewCreated] 的收集器）。 */
    private var builtYears: List<BookmarkYearFacet> = emptyList()

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
        // 内部状态推进顺序，不是契约。赌它 = 拿一个 UninitializedPropertyAccessException
        // 换一点代码量。恢复出来的空 sheet 本来也没有价值，直接关掉最干净。
        if (!viewModel.bound) {
            Timber.tag(TAG).w("VM 尚未绑定书架，关闭恢复出来的筛选面板")
            dismissAllowingStateLoss()
            return
        }
        buildSections()
        binding.resetButton.setOnClickListener {
            if (viewModel.clearConditions()) {
                // 两个搜索框都要跟着空掉：条件已经清了，框里却还留着字，界面就在说谎
                //（而且用户接着敲一个字，整串旧关键词会连着新字一起被重新应用）。
                // 宿主那个搜索框由宿主自己同步（见 BookmarkLibraryFragment.renderChips）。
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
                launch { viewModel.resultCount.collectLatest { updateApplyText(it) } }
                // facet 是异步算出来的：标签/作者两节先出骨架、算完再填，不阻塞面板打开
                launch { viewModel.tagFacets.collectLatest { rebuildTagChips() } }
                launch { viewModel.authorFacets.collectLatest { rebuildAuthorChips() } }
                // 年份分区要等 facet 算完才建得出来。**只在年份真的变了时才重建**：
                // StateFlow 订阅时会立刻重放当前值，无条件重建等于一开面板就把刚建好的
                // 十几节全部推倒重来一遍；后台补进新数据时同理，会把用户正在调的面板
                // 连滚动位置带标签搜索框一起清掉。
                launch {
                    viewModel.yearFacets.collectLatest { years ->
                        if (years != builtYears) buildSections()
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
        _binding = null
    }

    // ─────────────────────────── 各节 ───────────────────────────

    private fun buildSections() {
        val container = binding.sectionsContainer
        container.removeAllViews()
        refreshers.clear()
        builtYears = viewModel.yearFacets.value
        val isIllust = viewModel.shelf.contentType == MirrorContentType.ILLUST

        singleChoiceSection(
            title = getString(R.string.bookmark_filter_section_sort),
            options = sortOptions(isIllust),
            selected = { it.sort },
        ) { filter, value -> filter.copy(sort = value, randomSeed = freshSeedIfRandom(filter, value)) }

        if (isIllust) {
            multiChoiceSection(
                title = getString(R.string.bookmark_filter_section_type),
                options = listOf(
                    "illust" to getString(R.string.bookmark_filter_type_illust),
                    "manga" to getString(R.string.bookmark_filter_type_manga),
                    "ugoira" to getString(R.string.bookmark_filter_type_ugoira),
                ),
                selected = { it.workTypes.toSet() },
            ) { filter, values -> filter.copy(workTypes = values.toList()) }

            multiChoiceSection(
                title = getString(R.string.bookmark_filter_section_shape),
                options = listOf(
                    BookmarkMirrorMapper.ORIENTATION_LANDSCAPE to getString(R.string.bookmark_filter_shape_landscape),
                    BookmarkMirrorMapper.ORIENTATION_PORTRAIT to getString(R.string.bookmark_filter_shape_portrait),
                    BookmarkMirrorMapper.ORIENTATION_SQUARE to getString(R.string.bookmark_filter_shape_square),
                ),
                selected = { it.orientations.toSet() },
            ) { filter, values -> filter.copy(orientations = values.toList()) }

            singleChoiceSection(
                title = getString(R.string.bookmark_filter_section_pages),
                options = listOf(
                    PageFilter.ANY to getString(R.string.bookmark_filter_any),
                    PageFilter.SINGLE_PAGE to getString(R.string.bookmark_filter_pages_single),
                    PageFilter.MULTI_PAGE to getString(R.string.bookmark_filter_pages_multi),
                ),
                selected = { it.pages },
            ) { filter, value -> filter.copy(pages = value) }
        }

        if (!isIllust) {
            // 小说侧「人气」之外最实用的那一维：想找长篇 / 想找一口气看完的短篇。
            singleChoiceSection(
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

        singleChoiceSection(
            title = getString(R.string.bookmark_filter_section_age),
            options = listOf(
                AgeFilter.ANY to getString(R.string.bookmark_filter_any),
                AgeFilter.ALL_AGES to getString(R.string.bookmark_filter_age_all),
                AgeFilter.R18 to getString(R.string.bookmark_filter_age_r18),
                AgeFilter.R18G to getString(R.string.bookmark_filter_age_r18g),
            ),
            selected = { it.age },
        ) { filter, value -> filter.copy(age = value) }

        singleChoiceSection(
            title = getString(R.string.bookmark_filter_section_ai),
            options = listOf(
                AiFilter.ANY to getString(R.string.bookmark_filter_any),
                AiFilter.EXCLUDE_AI to getString(R.string.bookmark_filter_ai_exclude),
                AiFilter.ONLY_AI to getString(R.string.bookmark_filter_ai_only),
            ),
            selected = { it.ai },
        ) { filter, value -> filter.copy(ai = value) }

        singleChoiceSection(
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

        singleChoiceSection(
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

        val years = builtYears
        if (years.isNotEmpty()) {
            singleChoiceSection(
                title = getString(R.string.bookmark_filter_section_year),
                options = buildList {
                    add(null to getString(R.string.bookmark_filter_any))
                    years.forEach { facet ->
                        add(facet.year to getString(R.string.bookmark_filter_year_item, facet.year, facet.hitCount))
                    }
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

        toggleSection(
            title = getString(R.string.bookmark_filter_section_series),
            label = getString(R.string.bookmark_filter_series_only),
            selected = { it.seriesOnly },
        ) { filter, value -> filter.copy(seriesOnly = value) }

        buildTagSection(container)
        buildAuthorSection(container)

        refreshAll()
    }

    /** 排序项。随机每次重选都换一个种子，用户点第二下「随机」就是重新洗牌。 */
    private fun sortOptions(isIllust: Boolean): List<Pair<BookmarkSort, String>> = buildList {
        add(BookmarkSort.BOOKMARK_NEWEST to getString(R.string.bookmark_sort_bookmark_newest))
        add(BookmarkSort.BOOKMARK_OLDEST to getString(R.string.bookmark_sort_bookmark_oldest))
        add(BookmarkSort.CREATED_NEWEST to getString(R.string.bookmark_sort_created_newest))
        add(BookmarkSort.CREATED_OLDEST to getString(R.string.bookmark_sort_created_oldest))
        add(BookmarkSort.POPULAR_DESC to getString(R.string.bookmark_sort_popular_desc))
        add(BookmarkSort.POPULAR_ASC to getString(R.string.bookmark_sort_popular_asc))
        add(BookmarkSort.VIEWS_DESC to getString(R.string.bookmark_sort_views_desc))
        if (isIllust) {
            add(BookmarkSort.PAGES_DESC to getString(R.string.bookmark_sort_pages_desc))
            add(BookmarkSort.RATIO_TALLEST to getString(R.string.bookmark_sort_ratio_tallest))
            add(BookmarkSort.RATIO_WIDEST to getString(R.string.bookmark_sort_ratio_widest))
        } else {
            add(BookmarkSort.LENGTH_DESC to getString(R.string.bookmark_sort_length_desc))
            add(BookmarkSort.LENGTH_ASC to getString(R.string.bookmark_sort_length_asc))
        }
        add(BookmarkSort.TITLE_ASC to getString(R.string.bookmark_sort_title_asc))
        add(BookmarkSort.RANDOM to getString(R.string.bookmark_sort_random))
    }

    private fun freshSeedIfRandom(filter: BookmarkFilter, value: BookmarkSort): Long =
        if (value.isRandom) System.currentTimeMillis() else filter.randomSeed

    private fun buildTagSection(container: LinearLayout) {
        container.addView(sectionHeader(getString(R.string.bookmark_filter_section_tags)))
        container.addView(sectionHint(getString(R.string.bookmark_filter_tag_hint)))

        // 「同时满足 / 任一满足」：多标签的默认意图是收窄（AND），但「这几个系列随便哪个都行」
        // 也是真实需求，一个 chip 就能表达，不值得为它做二级菜单。
        val modeChip = chip(getString(R.string.bookmark_filter_tag_mode_all)) { chipView ->
            val nowAll = !viewModel.filter.value.tagMatchAll
            applyChange { it.copy(tagMatchAll = nowAll) }
            chipView.text = getString(
                if (nowAll) R.string.bookmark_filter_tag_mode_all else R.string.bookmark_filter_tag_mode_any
            )
        }
        refreshers += {
            val all = viewModel.filter.value.tagMatchAll
            modeChip.text = getString(
                if (all) R.string.bookmark_filter_tag_mode_all else R.string.bookmark_filter_tag_mode_any
            )
            modeChip.isActivated = viewModel.filter.value.tagNames.size > 1
        }

        val search = EditText(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_v3_chip)
            hint = getString(R.string.bookmark_filter_tag_search_hint)
            isSingleLine = true
            setTextColor(resources.getColor(R.color.v3_text_1, null))
            setHintTextColor(resources.getColor(R.color.v3_text_3, null))
            textSize = 13f
            updatePadding(left = dp(14), right = dp(14), top = dp(9), bottom = dp(9))
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
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeChip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(8) })
        }
        container.addView(row, matchWidth(topMarginDp = 8))

        tagFlow = FlowLayout(requireContext()).also { container.addView(it, matchWidth(topMarginDp = 8)) }
        rebuildTagChips()
    }

    private fun rebuildTagChips() {
        val flow = tagFlow ?: return
        flow.removeAllViews()
        val filter = viewModel.filter.value
        val facets = viewModel.tagFacets.value
        facets.forEach { knownTagLabels[it.tagName] = it.displayName to it.translatedName }

        // 排除掉的标签不在 facet 里（见 knownTagLabels），得自己补一份「幽灵 chip」出来，
        // 否则排除就是个单向操作，取消不掉。
        val entries = buildList {
            filter.excludedTagNames.forEach { name ->
                val (display, translated) = knownTagLabels[name] ?: (name to "")
                add(TagChipEntry(name, display, translated, hitCount = null, excluded = true))
            }
            facets.forEach { facet ->
                add(
                    TagChipEntry(
                        tagName = facet.tagName,
                        displayName = facet.displayName,
                        translatedName = facet.translatedName,
                        hitCount = facet.hitCount,
                        excluded = false,
                    )
                )
            }
        }
            // 已选中的钉在最前：标签云会随着每次下钻整体重排，选中的 chip 一旦被挤到
            // 几十个之后，用户就找不到自己刚点了什么、也退不回去了。
            .sortedByDescending { it.excluded || it.tagName in filter.tagNames }
            .filter { entry ->
                tagQuery.isEmpty() ||
                    entry.tagName.contains(tagQuery) ||
                    entry.translatedName.lowercase(Locale.ROOT).contains(tagQuery)
            }

        if (entries.isEmpty()) {
            flow.addView(sectionHint(getString(R.string.bookmark_filter_tag_empty)))
            return
        }
        entries.take(TAG_CHIP_LIMIT).forEach { entry ->
            val included = entry.tagName in filter.tagNames
            val excluded = entry.tagName in filter.excludedTagNames
            val label = buildString {
                if (excluded) append('−')
                append(entry.displayName)
                if (entry.translatedName.isNotEmpty() && entry.translatedName != entry.displayName) {
                    append(" · ").append(entry.translatedName)
                }
                entry.hitCount?.let { append("  ").append(it) }
            }
            val view = chip(label) {
                applyChange { current ->
                    // 点击在「不选 → 包含 → 不选」之间转；排除态点一下直接回到不选
                    when {
                        excluded -> current.copy(excludedTagNames = current.excludedTagNames - entry.tagName)
                        included -> current.copy(tagNames = current.tagNames - entry.tagName)
                        else -> current.copy(tagNames = current.tagNames + entry.tagName)
                    }
                }
                rebuildTagChips()
            }
            view.setOnLongClickListener {
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
                true
            }
            view.isActivated = included
            if (excluded) {
                view.setBackgroundResource(R.drawable.bg_bookmark_chip_excluded)
                view.setTextColor(resources.getColor(android.R.color.white, null))
            }
            flow.addView(view)
        }
    }

    /** 标签云里的一枚 chip。[hitCount] 为 null = 被排除的「幽灵项」，它已经不在结果里了。 */
    private class TagChipEntry(
        val tagName: String,
        val displayName: String,
        val translatedName: String,
        val hitCount: Int?,
        val excluded: Boolean,
    )

    private fun buildAuthorSection(container: LinearLayout) {
        container.addView(sectionHeader(getString(R.string.bookmark_filter_section_author)))
        authorFlow = FlowLayout(requireContext()).also { container.addView(it, matchWidth(topMarginDp = 8)) }
        rebuildAuthorChips()
    }

    private fun rebuildAuthorChips() {
        val flow = authorFlow ?: return
        flow.removeAllViews()
        val filter = viewModel.filter.value
        val facets = viewModel.authorFacets.value
        if (facets.isEmpty()) {
            flow.addView(sectionHint(getString(R.string.bookmark_filter_author_empty)))
            return
        }
        facets.forEach { facet ->
            val selected = facet.authorId in filter.authorIds
            val view = chip("${facet.authorName}  ${facet.hitCount}") {
                applyChange { current ->
                    current.copy(
                        authorIds = if (selected) current.authorIds - facet.authorId
                        else current.authorIds + facet.authorId
                    )
                }
                rebuildAuthorChips()
            }
            view.isActivated = selected
            flow.addView(view)
        }
    }

    // ───────────────────── 声明式的节构造器 ─────────────────────

    private fun <T> singleChoiceSection(
        title: String,
        options: List<Pair<T, String>>,
        selected: (BookmarkFilter) -> T,
        apply: (BookmarkFilter, T) -> BookmarkFilter,
    ) {
        val container = binding.sectionsContainer
        container.addView(sectionHeader(title))
        val flow = FlowLayout(requireContext())
        options.forEach { (value, label) ->
            val view = chip(label) {
                applyChange { apply(it, value) }
                refreshAll()
            }
            refreshers += { view.isActivated = selected(viewModel.filter.value) == value }
            flow.addView(view)
        }
        container.addView(flow, matchWidth(topMarginDp = 8))
    }

    private fun <T> multiChoiceSection(
        title: String,
        options: List<Pair<T, String>>,
        selected: (BookmarkFilter) -> Set<T>,
        apply: (BookmarkFilter, Set<T>) -> BookmarkFilter,
    ) {
        val container = binding.sectionsContainer
        container.addView(sectionHeader(title))
        val flow = FlowLayout(requireContext())
        options.forEach { (value, label) ->
            val view = chip(label) {
                applyChange { current ->
                    val now = selected(current)
                    apply(current, if (value in now) now - value else now + value)
                }
                refreshAll()
            }
            refreshers += { view.isActivated = value in selected(viewModel.filter.value) }
            flow.addView(view)
        }
        container.addView(flow, matchWidth(topMarginDp = 8))
    }

    private fun toggleSection(
        title: String,
        label: String,
        selected: (BookmarkFilter) -> Boolean,
        apply: (BookmarkFilter, Boolean) -> BookmarkFilter,
    ) {
        val container = binding.sectionsContainer
        container.addView(sectionHeader(title))
        val flow = FlowLayout(requireContext())
        val view = chip(label) {
            applyChange { apply(it, !selected(it)) }
            refreshAll()
        }
        refreshers += { view.isActivated = selected(viewModel.filter.value) }
        flow.addView(view)
        container.addView(flow, matchWidth(topMarginDp = 8))
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
        updateApplyText(viewModel.resultCount.value)
    }

    private fun updateApplyText(count: Int?) {
        _binding?.applyButton?.text = if (count == null) {
            getString(R.string.bookmark_library_filter_apply_pending)
        } else {
            getString(R.string.bookmark_library_filter_apply, formatCount(count))
        }
    }

    private fun chip(text: CharSequence, onClick: (TextView) -> Unit): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(resources.getColorStateList(R.color.bookmark_chip_text, null))
            setBackgroundResource(R.drawable.bg_bookmark_chip)
            updatePadding(left = dp(14), right = dp(14), top = dp(7), bottom = dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
            // FlowLayout 的子 View 间距靠 margin，它不认 gap 属性
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.rightMargin = dp(8)
                it.bottomMargin = dp(8)
            }
        }

    private fun sectionHeader(title: String): TextView = TextView(requireContext()).apply {
        text = title
        textSize = 13f
        setTextColor(resources.getColor(R.color.v3_text_3, null))
        layoutParams = matchWidth(topMarginDp = 14)
    }

    private fun sectionHint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(resources.getColor(R.color.v3_text_3, null))
        layoutParams = matchWidth(topMarginDp = 4)
    }

    private fun matchWidth(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(topMarginDp) }

    private fun dp(value: Int): Int = DensityUtil.dp2px(value.toFloat())

    private fun yearOf(epochMs: Long): Int = Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.YEAR)

    private fun yearStartMs(year: Int): Long = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
    }.timeInMillis

    companion object {
        private const val MAX_HEIGHT_FRACTION = 0.86

        /** 标签云一次最多铺这么多 chip：再多一屏也看不完，还会把 sheet 撑得滚不到底。 */
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
