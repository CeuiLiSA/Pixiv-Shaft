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
 * 「其他条件」子 sheet —— 收纳次要维度：AI 三选一（全部/屏蔽AI/仅看AI）+ 过滤已收藏二选一
 * + 小说专属（仅限原创 / 仅限单词置换 / 系列作品归纳）+ R-18 限制三选一。
 *
 * 小说专属三个 switch 仅在 isNovel = true 时显示；illust 模式整张卡片隐藏，结果回传时
 * 也固定 false。
 *
 * **AI 作品 / 过滤已收藏 =「单击临时、长按持久化」**（issue #909 的延伸）：
 *   - 单击只改本次搜索的档位（把 [SearchFilterV3.aiModeTemporary] /
 *     [SearchFilterV3.bookmarkFilterTemporary] 置 true），**不碰任何全局设置**——否则搜一次图
 *     就会顺带改掉首页等其它列表的 AI 屏蔽、以及设置页里的开关；
 *   - 长按某档才把该档写回全局设置（`isDeleteAIIllust` / `isSearchFilterBookmarked`），并把勾选
 *     同步到长按的那一行——长按的未必是当前勾选的那行；
 *   - 勾选值 ≠ 全局设置值时，勾的左边显示临时提示：能长按持久化的档位是「临时的（长按记住）」；
 *     「仅看 AI」没有全局对应档、长按也写不进设置，只显示「临时的」（不带括号里的那句）。
 *
 * draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢；结果走 FragmentResult API。
 */
class OtherFilterSheet : V3BottomSheetBase() {

    private var draftAiMode: AiMode = AiMode.All
    /** AI 档位是否为「会话临时值」（true = 单击选的，不写设置；长按某档会把它写回设置并归 false）。 */
    private var draftAiModeTemporary: Boolean = false
    private var draftR18: R18Mode = R18Mode.All
    private var draftOriginalOnly: Boolean = false
    private var draftReplaceableOnly: Boolean = false
    private var draftGroupBySeries: Boolean = false
    /** 「过滤已收藏」草稿——单击只改本次搜索，长按才写回全局 isSearchFilterBookmarked。 */
    private var draftBookmarkFilter: Boolean = false
    /** 「过滤已收藏」是否为「会话临时值」，语义同 [draftAiModeTemporary]。 */
    private var draftBookmarkFilterTemporary: Boolean = false
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
        /** AI 档位是否为「会话临时值」（单击改、不写设置）。 */
        val aiModeTemporary: Boolean = false,
        /** 「过滤已收藏」是否为「会话临时值」。 */
        val bookmarkFilterTemporary: Boolean = false,
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
        draftAiModeTemporary = patch?.aiModeTemporary ?: false
        draftR18 = patch?.r18Mode ?: R18Mode.All
        draftOriginalOnly = patch?.isOriginalOnly ?: false
        draftReplaceableOnly = patch?.isReplaceableOnly ?: false
        draftGroupBySeries = patch?.groupBySeries ?: false
        draftBookmarkFilter = patch?.bookmarkFilter ?: Shaft.sSettings.isSearchFilterBookmarked
        draftBookmarkFilterTemporary = patch?.bookmarkFilterTemporary ?: false
        draftTool = patch?.tool
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT,
            Patch(draftAiMode, draftR18, draftOriginalOnly, draftReplaceableOnly, draftTool,
                draftGroupBySeries, draftBookmarkFilter,
                draftAiModeTemporary, draftBookmarkFilterTemporary))
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
            // 单击只产生「本次搜索的临时档位」，**绝不在这里回灌持久化**——AI 的「全部 / 屏蔽AI」
            // 与「过滤已收藏」都只在用户长按某档时才写回设置（见 bindAiRow / bindBookmarkRow）。
            // 否则搜一次图就会顺带改掉首页等其它列表的 AI 屏蔽、以及设置页里的开关。
            // 「仅看 AI」官方无对应档，本来就是临时维度（issue #909）。
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
                    draftBookmarkFilter, draftAiModeTemporary, draftBookmarkFilterTemporary,
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

    /** 全局设置当前对应的 AI 档位（「仅看 AI」没有全局对应档，故只有这两档）。 */
    private fun persistedAiMode(): AiMode =
        if (Shaft.sSettings.isDeleteAIIllust) AiMode.ExcludeAi else AiMode.All

    /**
     * AI 行：单击 = 本次搜索的临时档（不写设置）；长按 = 把该档写回全局设置，并把勾选同步到它
     * （长按的那行不一定就是当前勾选的那行）。
     */
    private fun bindAiRow(row: CellSearchFilterCheckRowBinding, mode: AiMode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        // 临时提示与勾同走主题色（accent），不再用次级文字色
        row.checkHint.setTextColor(palette.textAccent)
        // 「仅看 AI」没有全局对应档，长按也写不进设置 → 提示不带「（长按记住）」
        row.checkHint.setText(
            if (mode == AiMode.OnlyAi) R.string.search_filter_v3_row_temporary_hint_no_persist
            else R.string.search_filter_v3_row_temporary_hint
        )
        row.root.setOnClick {
            draftAiMode = mode
            draftAiModeTemporary = mode != persistedAiMode()
            renderAiMarks()
        }
        row.root.setOnLongClickListener {
            draftAiMode = mode
            if (mode == AiMode.OnlyAi) {
                // 「仅看 AI」没有全局对应档，写不进设置——只能作为本次搜索的临时档
                draftAiModeTemporary = true
            } else {
                val exclude = mode == AiMode.ExcludeAi
                if (Shaft.sSettings.isDeleteAIIllust != exclude) {
                    Shaft.sSettings.isDeleteAIIllust = exclude
                    Local.setSettings(Shaft.sSettings)
                }
                draftAiModeTemporary = false
            }
            renderAiMarks()
            true
        }
    }

    private fun renderAiMarks() {
        binding.rowAiAll.checkMark.isInvisible     = draftAiMode != AiMode.All
        binding.rowAiExclude.checkMark.isInvisible = draftAiMode != AiMode.ExcludeAi
        binding.rowAiOnly.checkMark.isInvisible    = draftAiMode != AiMode.OnlyAi
        // 提示只在「勾选值 ≠ 全局设置值」时出现——那才说明这一档是本次搜索的临时值
        val showHint = draftAiMode != persistedAiMode()
        binding.rowAiAll.checkHint.isVisible     = showHint && draftAiMode == AiMode.All
        binding.rowAiExclude.checkHint.isVisible = showHint && draftAiMode == AiMode.ExcludeAi
        binding.rowAiOnly.checkHint.isVisible    = showHint && draftAiMode == AiMode.OnlyAi
    }

    /** 全局设置当前对应的「过滤已收藏」档位。 */
    private fun persistedBookmarkFilter(): Boolean = Shaft.sSettings.isSearchFilterBookmarked

    /** 过滤已收藏行：单击 = 临时档（不写设置）；长按 = 写回全局设置并同步勾选。 */
    private fun bindBookmarkRow(row: CellSearchFilterCheckRowBinding, filter: Boolean, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.checkHint.setTextColor(palette.textAccent)
        row.checkHint.setText(R.string.search_filter_v3_row_temporary_hint)
        row.root.setOnClick {
            draftBookmarkFilter = filter
            draftBookmarkFilterTemporary = filter != persistedBookmarkFilter()
            renderBookmarkMarks()
        }
        row.root.setOnLongClickListener {
            draftBookmarkFilter = filter
            if (Shaft.sSettings.isSearchFilterBookmarked != filter) {
                Shaft.sSettings.isSearchFilterBookmarked = filter
                Local.setSettings(Shaft.sSettings)
            }
            draftBookmarkFilterTemporary = false
            renderBookmarkMarks()
            true
        }
    }

    private fun renderBookmarkMarks() {
        binding.rowBookmarkAll.checkMark.isInvisible  = draftBookmarkFilter
        binding.rowBookmarkOnly.checkMark.isInvisible = !draftBookmarkFilter
        // 同 AI 行：只在「勾选值 ≠ 全局设置值」时把临时提示挂到勾的左边
        val showHint = draftBookmarkFilter != persistedBookmarkFilter()
        binding.rowBookmarkAll.checkHint.isVisible  = showHint && !draftBookmarkFilter
        binding.rowBookmarkOnly.checkHint.isVisible = showHint && draftBookmarkFilter
    }

    private fun bindR18Row(row: CellSearchFilterCheckRowBinding, mode: R18Mode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.root.setOnClick {
            draftR18 = mode
            renderR18Marks()
        }
    }

    private fun renderR18Marks() {
        binding.rowR18All.checkMark.isInvisible  = draftR18 != R18Mode.All
        binding.rowR18Safe.checkMark.isInvisible = draftR18 != R18Mode.SafeOnly
        binding.rowR18Only.checkMark.isInvisible = draftR18 != R18Mode.R18Only
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
                    current.aiModeTemporary,
                    current.bookmarkFilterTemporary,
                ))
            }
        }
    }
}
