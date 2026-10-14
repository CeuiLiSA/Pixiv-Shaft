package ceui.pixiv.ui.search.v3

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.CellSearchFilterSwitchRowBinding
import ceui.lisa.databinding.DialogSearchFilterOtherBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.utils.setOnClick
import java.io.Serializable

/**
 * 「其他条件」子 sheet —— 收纳次要维度：AI 三选一（全部/不显示AI/仅看AI）+ 过滤已收藏二选一
 * + 小说专属（仅限原创 / 仅限单词置换 / 系列作品归纳）+ R-18 限制三选一。
 *
 * 小说专属三个 switch 仅在 isNovel = true 时显示；illust 模式整张卡片隐藏，结果回传时
 * 也固定 false。
 *
 * **三层持久化（issue #1240）** —— AI 作品 / 过滤已收藏 / R-18 三个维度的勾选行统一按
 * [FilterPersist] 三层走，手势是「单击 = 临时、长按 = 记一档」：
 *   - **单击 = 临时**：只改本次搜索的档位，**不落任何盘**（[FilterPersist.Temporary]）；
 *   - **长按第 1 下 = 仅这里记住**：写搜索页专属记忆（[FilterPersist.RememberHere]），跨重启
 *     只被搜索页读，**不碰全局设置**——否则搜一次图就会顺带改掉首页等其它列表的 AI 屏蔽、
 *     以及设置页里的开关；
 *   - **长按第 2 下 = 全局记住**：把该档写回全局设置（`isDeleteAIIllust` /
 *     `isSearchFilterBookmarked`），并把勾选同步到长按的那一行——长按的未必是当前勾选的那行；
 *   - 没有全局对应档的维度（**仅看 AI**、**R-18 三档**）到「仅这里记住」封顶，第 2 下长按只弹
 *     toast 说明，不会假装写进一个并不存在的全局设置。
 *
 * 打开时的取值优先级：**搜索页「仅这里记住」→ 全局设置 → 内置默认**（见
 * [SearchFilterV3.fromGlobalDefaults]）。
 *
 * draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢；结果走 FragmentResult API。
 */
class OtherFilterSheet : V3BottomSheetBase() {

    private var draftAiMode: AiMode = AiMode.All
    /** AI 档位的持久化层级（临时 / 仅这里记住 / 全局记住）。 */
    private var draftAiModePersist: FilterPersist = FilterPersist.Global
    private var draftR18: R18Mode = R18Mode.All
    /** R-18 档位的持久化层级；无全局档，最多到「仅这里记住」。 */
    private var draftR18Persist: FilterPersist = FilterPersist.Global
    private var draftOriginalOnly: Boolean = false
    private var draftReplaceableOnly: Boolean = false
    private var draftGroupBySeries: Boolean = false
    /** 「过滤已收藏」草稿。 */
    private var draftBookmarkFilter: Boolean = false
    /** 「过滤已收藏」的持久化层级。 */
    private var draftBookmarkFilterPersist: FilterPersist = FilterPersist.Global
    /** illust-only;null = 「不限」。父 sheet 通过 args 注入初值 + 候选列表。 */
    private var draftTool: String? = null

    private val isNovel: Boolean
        get() = requireArguments().getBoolean(ARG_IS_NOVEL, false)

    /**
     * 实时读 parent sheet 持有的 SearchViewModel.searchOptions —— 不再用 args snapshot,
     * 这样即便用户进 sheet 时 /v1/search/options 还没拉到,options 一到位下一次点击就生效。
     * Cast 不上时回退空 list（自身被遗弃/单独显示时 fail-safe）。
     */
    private fun currentToolOptions(): List<String> =
        (parentFragment as? SearchFilterV3BottomSheet)
            ?.searchViewModel?.searchOptions?.value?.illust?.tool?.options.orEmpty()

    data class Patch(
        val aiMode: AiMode,
        val r18Mode: R18Mode,
        val isOriginalOnly: Boolean,
        val isReplaceableOnly: Boolean,
        val tool: String?,
        val groupBySeries: Boolean = false,
        val bookmarkFilter: Boolean = false,
        /** AI 档位的持久化层级（issue #1240）。 */
        val aiModePersist: FilterPersist = FilterPersist.Global,
        /** 「过滤已收藏」的持久化层级。 */
        val bookmarkFilterPersist: FilterPersist = FilterPersist.Global,
        /** R-18 档位的持久化层级。 */
        val r18ModePersist: FilterPersist = FilterPersist.Global,
    ) : Serializable

    private var _binding: DialogSearchFilterOtherBinding? = null
    private val binding get() = _binding!!

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: requireArguments()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val patch = source.getSerializable(KEY_DRAFT) as? Patch
            ?: @Suppress("DEPRECATION") (requireArguments().getSerializable(ARG_INITIAL) as? Patch)
        draftAiMode = patch?.aiMode ?: AiMode.All
        draftAiModePersist = patch?.aiModePersist ?: FilterPersist.Global
        draftR18 = patch?.r18Mode ?: R18Mode.All
        draftR18Persist = patch?.r18ModePersist ?: FilterPersist.Global
        draftOriginalOnly = patch?.isOriginalOnly ?: false
        draftReplaceableOnly = patch?.isReplaceableOnly ?: false
        draftGroupBySeries = patch?.groupBySeries ?: false
        draftBookmarkFilter = patch?.bookmarkFilter ?: Shaft.sSettings.isSearchFilterBookmarked
        draftBookmarkFilterPersist = patch?.bookmarkFilterPersist ?: FilterPersist.Global
        draftTool = patch?.tool
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT,
            Patch(draftAiMode, draftR18, draftOriginalOnly, draftReplaceableOnly, draftTool,
                draftGroupBySeries, draftBookmarkFilter,
                draftAiModePersist, draftBookmarkFilterPersist, draftR18Persist))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterOtherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnConfirm.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnConfirm.setOnClick {
            // 单击 / 长按的落盘都在行监听里当场做完（见 bindAiRow / bindBookmarkRow / bindR18Row），
            // 「确定」只把本次搜索要用的档位 + 它们的持久化层级原样回传，**绝不在这里回灌持久化**。
            // 小说专属 switch：illust 模式下卡片整体隐藏，强制 false 防止状态串味儿
            val originalOnly = if (isNovel) draftOriginalOnly else false
            val replaceableOnly = if (isNovel) draftReplaceableOnly else false
            val groupBySeries = if (isNovel) draftGroupBySeries else false
            // 制图工具同理：novel 模式整张卡片隐藏，强制清 null
            val tool = if (isNovel) null else draftTool
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(KEY_PATCH to Patch(
                    draftAiMode, draftR18, originalOnly, replaceableOnly, tool, groupBySeries,
                    draftBookmarkFilter,
                    draftAiModePersist, draftBookmarkFilterPersist, draftR18Persist,
                )),
            )
            dismissAllowingStateLoss()
        }

        // AI section —— 三选一：全部 / 屏蔽 AI / 仅看 AI
        bindAiRow(binding.rowAiAll,     AiMode.All,       R.string.search_filter_v3_ai_all)
        bindAiRow(binding.rowAiExclude, AiMode.ExcludeAi, R.string.search_filter_v3_ai_exclude)
        bindAiRow(binding.rowAiOnly,    AiMode.OnlyAi,    R.string.search_filter_v3_ai_only)
        renderAiMarks()

        // 过滤已收藏 —— 二选一：不过滤 / 过滤（与全局设置联动，illust / novel 都显示）
        bindBookmarkRow(binding.rowBookmarkAll,  false, R.string.search_filter_v3_bookmark_filter_all)
        bindBookmarkRow(binding.rowBookmarkOnly, true,  R.string.search_filter_v3_bookmark_filter_only)
        renderBookmarkMarks()

        // 制图工具（illust/manga 专属）—— novel 模式整张卡片隐藏
        binding.illustToolSpace.isVisible = !isNovel
        binding.illustToolCard.isVisible = !isNovel
        if (!isNovel) {
            binding.rowTool.rowValue.setTextColor(palette.textAccent)
            renderToolRow()
            binding.rowTool.root.setOnClick { showToolPicker() }
            childFragmentManager.setFragmentResultListener(REQUEST_TOOL_PICKER, this) { _, bundle ->
                val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
                // idx 0 = "不限"，1.. = currentToolOptions()[idx-1]
                // 读 picker 展示时的同一份 options(此刻 options 必非空,picker 是从 currentToolOptions
                // build 出来的);用 currentToolOptions() 比缓存 args 更不容易过期
                val opts = currentToolOptions()
                draftTool = if (idx == 0) null else opts.getOrNull(idx - 1)
                renderToolRow()
            }
        }

        // 小说专属：仅限原创 / 仅限单词置换
        binding.novelSectionSpace.isVisible = isNovel
        binding.novelSectionCard.isVisible = isNovel
        if (isNovel) {
            bindNovelSwitch(
                binding.rowOriginalOnly,
                R.string.search_filter_v3_row_original_only,
                draftOriginalOnly,
            ) { draftOriginalOnly = it }
            bindNovelSwitch(
                binding.rowReplaceableOnly,
                R.string.search_filter_v3_row_replaceable_only,
                draftReplaceableOnly,
            ) { draftReplaceableOnly = it }
            // 系列归纳这行**保留副标题**：开启后整条小说搜索改走网页接口，热门排序与 R-18
            // 的可用性都跟着变（issue #1016），不写清楚用户只会以为搜索坏了。
            bindNovelSwitch(
                binding.rowGroupBySeries,
                R.string.search_filter_v3_row_group_by_series,
                draftGroupBySeries,
                subtitleRes = R.string.search_filter_v3_group_by_series_subtitle,
            ) { draftGroupBySeries = it }
        }

        // R-18 三选一
        bindR18Row(binding.rowR18All,  R18Mode.All,      R.string.search_filter_v3_r18_all)
        bindR18Row(binding.rowR18Safe, R18Mode.SafeOnly, R.string.search_filter_v3_r18_safe)
        bindR18Row(binding.rowR18Only, R18Mode.R18Only,  R.string.search_filter_v3_r18_only)
        renderR18Marks()
    }

    private fun renderToolRow() {
        binding.rowTool.rowTitle.setText(R.string.search_filter_v3_row_tool)
        binding.rowTool.rowValue.text =
            draftTool ?: getString(R.string.search_filter_v3_tool_all_summary)
    }

    private fun showToolPicker() {
        val opts = currentToolOptions()
        if (opts.isEmpty()) {
            // /v1/search/options 还没回 —— 重启一次拉取 + toast 让用户稍后再试,
            // 不再像旧版那样一声不吭地吃掉点击
            (parentFragment as? SearchFilterV3BottomSheet)?.ensureSearchOptionsLoaded()
            Common.showToast(getString(R.string.search_filter_v3_tool_loading))
            return
        }
        val labels = listOf(getString(R.string.search_filter_v3_tool_all_summary)) + opts
        val selected = draftTool?.let {
            val idx = opts.indexOf(it)
            if (idx < 0) 0 else idx + 1
        } ?: 0
        SimplePickerSheet.newInstance(
            REQUEST_TOOL_PICKER,
            getString(R.string.search_filter_v3_row_tool),
            labels,
            selected,
        ).show(childFragmentManager, REQUEST_TOOL_PICKER)
    }

    private fun bindNovelSwitch(
        row: CellSearchFilterSwitchRowBinding,
        titleRes: Int,
        initial: Boolean,
        subtitleRes: Int? = null,
        onChange: (Boolean) -> Unit,
    ) {
        row.switchTitle.setText(titleRes)
        // 对齐 iOS 的两行没有副标题，隐藏掉 cell_search_filter_switch_row 自带的 subtitle 槽位；
        // 系列归纳那行要靠它讲清接口切换的代价，显式给了 subtitleRes 才显示。
        row.switchSubtitle.isVisible = subtitleRes != null
        subtitleRes?.let { row.switchSubtitle.setText(it) }
        row.switchToggle.thumbTintList = ColorStateList.valueOf(palette.primary)
        row.switchToggle.isChecked = initial
        row.switchToggle.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    // ── 三层持久化（issue #1240）────────────────────────────────────────────
    //
    // 单击 = 临时（只作用本次搜索，不落盘）；长按第 1 下 = 仅这里记住（写搜索页专属记忆，跨重启
    // 只被搜索页读）；长按第 2 下 = 全局记住（写全局设置，仅"有全局档"的维度）。没有全局对应档
    // 的档位到「仅这里记住」封顶，第 2 下长按只弹 toast。取值优先级：页记 → 全局 → 内置默认。

    /** 全局设置当前对应的 AI 档位（「仅看 AI」没有全局对应档，故只有这两档）。 */
    private fun persistedAiMode(): AiMode =
        if (Shaft.sSettings.isDeleteAIIllust) AiMode.ExcludeAi else AiMode.All

    /** 全局设置当前对应的「过滤已收藏」档位。 */
    private fun persistedBookmarkFilter(): Boolean = Shaft.sSettings.isSearchFilterBookmarked

    /** R-18 没有全局对应档，基线恒为内置默认「全部」。 */
    private fun persistedR18Mode(): R18Mode = R18Mode.All

    /** 该档位有没有全局对应设置——决定长按第 2 下能不能落到「全局记住」。 */
    private fun AiMode.hasGlobalTier(): Boolean = this != AiMode.OnlyAi

    /** 搜索页「仅这里记住」的 AI 档位；null = 没记住过。 */
    private fun rememberedAiMode(): AiMode? = Shaft.sSettings.searchRememberedAiMode
        ?.let { name -> AiMode.entries.firstOrNull { it.name == name } }

    /** 搜索页「仅这里记住」的「过滤已收藏」档位；null = 没记住过。 */
    private fun rememberedBookmarkFilter(): Boolean? =
        Shaft.sSettings.searchRememberedBookmarkFilter?.toBooleanStrictOrNull()

    /** 搜索页「仅这里记住」的 R-18 档位；null = 没记住过。 */
    private fun rememberedR18Mode(): R18Mode? = Shaft.sSettings.searchRememberedR18Mode
        ?.let { name -> R18Mode.entries.firstOrNull { it.name == name } }

    /**
     * 单击某档后它应处的持久化层级：
     *   - 等于「页记」值 → [FilterPersist.RememberHere]（这一档已经记住在这儿了）；
     *   - 否则等于全局 / 内置默认基线 → [FilterPersist.Global]（没覆盖，跟随基线）；
     *   - 其余 → [FilterPersist.Temporary]（只作用本次搜索）。
     */
    private fun tierAfterClick(value: Any?, remembered: Any?, baseline: Any?): FilterPersist = when {
        remembered != null && value == remembered -> FilterPersist.RememberHere
        value == baseline -> FilterPersist.Global
        else -> FilterPersist.Temporary
    }

    /**
     * 长按推进一档：临时 → 仅这里记住 → 全局记住。没有全局档的档位到「仅这里记住」封顶，第 2 下
     * 长按只弹 toast 说明；已在「全局记住」时不再变化（那是持久化上限，单击可退回临时）。
     *
     * [current] 必须是**被长按那一档自己**的层级（先走一遍单击口径 select* 再传进来）——长按的
     * 未必是当前勾选的那行，拿上一行的层级来推进会把没记住的档位标成「全局」/「仅这里记住」。
     */
    private fun advancePersist(
        current: FilterPersist,
        hasGlobal: Boolean,
        onRememberHere: () -> Unit,
        onGlobal: () -> Unit,
    ): FilterPersist = when (current) {
        FilterPersist.Temporary -> {
            onRememberHere()
            FilterPersist.RememberHere
        }
        FilterPersist.RememberHere -> if (hasGlobal) {
            onGlobal()
            FilterPersist.Global
        } else {
            Common.showToast(getString(R.string.search_filter_v3_row_no_global_toast))
            FilterPersist.RememberHere
        }
        FilterPersist.Global -> FilterPersist.Global
    }

    /** 写搜索页专属记忆（「仅这里记住」）。 */
    private fun rememberAiMode(mode: AiMode) {
        Shaft.sSettings.searchRememberedAiMode = mode.name
        Local.setSettings(Shaft.sSettings)
    }

    private fun rememberBookmarkFilter(filter: Boolean) {
        Shaft.sSettings.searchRememberedBookmarkFilter = filter.toString()
        Local.setSettings(Shaft.sSettings)
    }

    private fun rememberR18Mode(mode: R18Mode) {
        Shaft.sSettings.searchRememberedR18Mode = mode.name
        Local.setSettings(Shaft.sSettings)
    }

    /** 清掉搜索页专属记忆——落到「全局记住」或对齐基线时调用，免得旧记忆盖住刚写下的全局值。 */
    private fun clearRememberedAiMode() {
        if (Shaft.sSettings.searchRememberedAiMode != null) {
            Shaft.sSettings.searchRememberedAiMode = null
            Local.setSettings(Shaft.sSettings)
        }
    }

    private fun clearRememberedBookmarkFilter() {
        if (Shaft.sSettings.searchRememberedBookmarkFilter != null) {
            Shaft.sSettings.searchRememberedBookmarkFilter = null
            Local.setSettings(Shaft.sSettings)
        }
    }

    private fun clearRememberedR18Mode() {
        if (Shaft.sSettings.searchRememberedR18Mode != null) {
            Shaft.sSettings.searchRememberedR18Mode = null
            Local.setSettings(Shaft.sSettings)
        }
    }

    /** 把 AI 档写回全局设置（`isDeleteAIIllust`），并清掉页记。 */
    private fun persistAiModeToGlobal(mode: AiMode) {
        Shaft.sSettings.isDeleteAIIllust = mode == AiMode.ExcludeAi
        Shaft.sSettings.searchRememberedAiMode = null
        Local.setSettings(Shaft.sSettings)
    }

    /** 把「过滤已收藏」写回全局设置（`isSearchFilterBookmarked`），并清掉页记。 */
    private fun persistBookmarkFilterToGlobal(filter: Boolean) {
        Shaft.sSettings.isSearchFilterBookmarked = filter
        Shaft.sSettings.searchRememberedBookmarkFilter = null
        Local.setSettings(Shaft.sSettings)
    }

    /**
     * 把某行的持久化层级渲染到勾左侧的提示上（只对当前勾选的那行可见）：
     *   - 临时 → 「临时的（长按记住）」；
     *   - 仅这里记住 → 「仅这里记住」；有全局档的再加「（长按全局）」说明还能再长按；
     *   - 全局 → 不显示（已落全局 / 基线＝默认态）。
     */
    private fun bindTierHint(
        row: CellSearchFilterCheckRowBinding,
        visible: Boolean,
        tier: FilterPersist,
        hasGlobal: Boolean,
    ) {
        row.checkHint.isVisible = visible && tier != FilterPersist.Global
        row.checkHint.setText(when (tier) {
            FilterPersist.Temporary -> R.string.search_filter_v3_row_temporary_hint
            FilterPersist.RememberHere ->
                if (hasGlobal) R.string.search_filter_v3_row_remember_here_global_hint
                else R.string.search_filter_v3_row_remember_here_hint
            FilterPersist.Global -> R.string.search_filter_v3_row_remember_here_hint
        })
    }

    /**
     * AI 行：单击 = 本次搜索的临时档；长按第 1 下 = 仅这里记住；长按第 2 下 = 写回全局设置。
     * 「仅看 AI」没有全局对应档，长按只到「仅这里记住」。
     */
    private fun bindAiRow(row: CellSearchFilterCheckRowBinding, mode: AiMode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        // 层级提示与勾同走主题色（accent），不用次级文字色
        row.checkHint.setTextColor(palette.textAccent)
        row.root.setOnClick {
            selectAiMode(mode)
            renderAiMarks()
        }
        row.root.setOnLongClickListener {
            selectAiMode(mode)
            draftAiModePersist = advancePersist(
                current = draftAiModePersist,
                hasGlobal = mode.hasGlobalTier(),
                onRememberHere = { rememberAiMode(mode) },
                onGlobal = { persistAiModeToGlobal(mode) },
            )
            renderAiMarks()
            true
        }
    }

    /** 单击口径：勾上 [mode]，层级按这一档自己当前存在哪定（见 [tierAfterClick]）。 */
    private fun selectAiMode(mode: AiMode) {
        draftAiMode = mode
        draftAiModePersist = tierAfterClick(mode, rememberedAiMode(), persistedAiMode())
        // 落回「全局」＝不再覆盖 → 清掉页记，免得旧记忆盖住刚对齐的基线
        if (draftAiModePersist == FilterPersist.Global) clearRememberedAiMode()
    }

    private fun renderAiMarks() {
        binding.rowAiAll.checkMark.isInvisible     = draftAiMode != AiMode.All
        binding.rowAiExclude.checkMark.isInvisible = draftAiMode != AiMode.ExcludeAi
        binding.rowAiOnly.checkMark.isInvisible    = draftAiMode != AiMode.OnlyAi
        bindTierHint(
            binding.rowAiAll, draftAiMode == AiMode.All,
            draftAiModePersist, AiMode.All.hasGlobalTier(),
        )
        bindTierHint(
            binding.rowAiExclude, draftAiMode == AiMode.ExcludeAi,
            draftAiModePersist, AiMode.ExcludeAi.hasGlobalTier(),
        )
        bindTierHint(
            binding.rowAiOnly, draftAiMode == AiMode.OnlyAi,
            draftAiModePersist, AiMode.OnlyAi.hasGlobalTier(),
        )
    }

    /** 过滤已收藏行：单击 = 临时；长按第 1 下 = 仅这里记住；长按第 2 下 = 写回全局设置。 */
    private fun bindBookmarkRow(row: CellSearchFilterCheckRowBinding, filter: Boolean, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.checkHint.setTextColor(palette.textAccent)
        row.root.setOnClick {
            selectBookmarkFilter(filter)
            renderBookmarkMarks()
        }
        row.root.setOnLongClickListener {
            selectBookmarkFilter(filter)
            draftBookmarkFilterPersist = advancePersist(
                current = draftBookmarkFilterPersist,
                hasGlobal = true,
                onRememberHere = { rememberBookmarkFilter(filter) },
                onGlobal = { persistBookmarkFilterToGlobal(filter) },
            )
            renderBookmarkMarks()
            true
        }
    }

    /** 单击口径，同 [selectAiMode]。 */
    private fun selectBookmarkFilter(filter: Boolean) {
        draftBookmarkFilter = filter
        draftBookmarkFilterPersist =
            tierAfterClick(filter, rememberedBookmarkFilter(), persistedBookmarkFilter())
        if (draftBookmarkFilterPersist == FilterPersist.Global) clearRememberedBookmarkFilter()
    }

    private fun renderBookmarkMarks() {
        binding.rowBookmarkAll.checkMark.isInvisible  = draftBookmarkFilter
        binding.rowBookmarkOnly.checkMark.isInvisible = !draftBookmarkFilter
        bindTierHint(
            binding.rowBookmarkAll, !draftBookmarkFilter, draftBookmarkFilterPersist, hasGlobal = true,
        )
        bindTierHint(
            binding.rowBookmarkOnly, draftBookmarkFilter, draftBookmarkFilterPersist, hasGlobal = true,
        )
    }

    /** R-18 行：单击 = 临时；长按第 1 下 = 仅这里记住；无全局档，长按第 2 下只弹 toast。 */
    private fun bindR18Row(row: CellSearchFilterCheckRowBinding, mode: R18Mode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.checkHint.setTextColor(palette.textAccent)
        row.root.setOnClick {
            selectR18Mode(mode)
            renderR18Marks()
        }
        row.root.setOnLongClickListener {
            selectR18Mode(mode)
            draftR18Persist = advancePersist(
                current = draftR18Persist,
                hasGlobal = false,
                onRememberHere = { rememberR18Mode(mode) },
                onGlobal = { },
            )
            renderR18Marks()
            true
        }
    }

    /** 单击口径，同 [selectAiMode]。 */
    private fun selectR18Mode(mode: R18Mode) {
        draftR18 = mode
        draftR18Persist = tierAfterClick(mode, rememberedR18Mode(), persistedR18Mode())
        if (draftR18Persist == FilterPersist.Global) clearRememberedR18Mode()
    }

    private fun renderR18Marks() {
        binding.rowR18All.checkMark.isInvisible  = draftR18 != R18Mode.All
        binding.rowR18Safe.checkMark.isInvisible = draftR18 != R18Mode.SafeOnly
        binding.rowR18Only.checkMark.isInvisible = draftR18 != R18Mode.R18Only
        bindTierHint(binding.rowR18All, draftR18 == R18Mode.All, draftR18Persist, hasGlobal = false)
        bindTierHint(binding.rowR18Safe, draftR18 == R18Mode.SafeOnly, draftR18Persist, hasGlobal = false)
        bindTierHint(binding.rowR18Only, draftR18 == R18Mode.R18Only, draftR18Persist, hasGlobal = false)
    }

    companion object {
        /** 结果 Bundle 里 [Patch] 的 key。 */
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL = "initial"
        private const val ARG_IS_NOVEL = "isNovel"
        private const val KEY_DRAFT = "draft"

        // 内部 picker 的 request key,与父 sheet REQUEST_OTHER 互不重叠
        private const val REQUEST_TOOL_PICKER = "v3_other_tool_picker"

        fun newInstance(
            requestKey: String,
            current: SearchFilterV3,
            isNovel: Boolean,
        ): OtherFilterSheet = OtherFilterSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(ARG_IS_NOVEL, isNovel)
                putSerializable(ARG_INITIAL, Patch(
                    current.aiMode,
                    current.r18Mode,
                    current.isOriginalOnly,
                    current.isReplaceableOnly,
                    current.tool,
                    current.groupBySeries,
                    current.bookmarkFilter,
                    current.aiModePersist,
                    current.bookmarkFilterPersist,
                    current.r18ModePersist,
                ))
            }
        }
    }
}
