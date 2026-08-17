package ceui.pixiv.ui.synonym

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.TagsBean
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.db.synonym.SynonymMatcher
import ceui.pixiv.db.synonym.TargetWithSynonyms
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import java.util.concurrent.Executors

/**
 * 「标签匹配关系」框（issue #904）—— 作品详情页 tags 区下方的预期收藏夹匹配展示。
 *
 * 展示规则：
 * - 匹配到的目标标签：只展示命中的同义词（accent 色），按作品自带标签的显示顺序排列；
 *   未命中的折叠成一行「另有 N 个同义词未命中」，点击原地展开成灰色 chip
 *   （issue #905：内置词典单个目标可达 10+ 个同义词，全量渲染挤占版面）
 * - 未匹配的目标标签：折叠成一行「另有 N 个目标标签未匹配 ›」，点击跳转管理页
 *   （issue 原文要求全部显示，但导入预生成词典后目标可达数千个，全量渲染会 ANR/OOM，
 *   故未匹配项只显示计数 —— 与用户确认过的方案）
 * - 长按目标标签 / 同义词 chip 进入管理菜单（SynonymOperate）
 * - 词典为空或无作品标签时整个 view 隐藏
 *
 * 共享组件：FragmentIllust / ArtworkV3 / FragmentNovelHolder 都挂这一个，
 * 保证多套详情页行为一致。颜色全部走 [V3Palette] + v3_* 资源，日夜双模式自动适配。
 *
 * 线程模型：词典 LiveData 主线程回调 → 匹配计算丢到 [matchExecutor]（词典可能数千组）→
 * 结果 post 回主线程渲染；[renderSeq] 防止过期结果覆盖新结果。
 */
class SynonymMatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val palette by lazy { V3Palette.from(context) }
    private var workTagPairs: List<Pair<String, String?>> = emptyList()
    private var dictionary: List<TargetWithSynonyms> = emptyList()

    /** 匹配计算的代次：每次输入变化 +1，后台算完只渲染最新代次的结果 */
    @Volatile
    private var renderSeq = 0

    private var dictLive: LiveData<List<TargetWithSynonyms>>? = null
    private val dictObserver = Observer<List<TargetWithSynonyms>> { list ->
        dictionary = list ?: emptyList()
        scheduleMatch()
    }

    init {
        orientation = VERTICAL
        setPaddingRelative(12.ppppx, 0, 12.ppppx, 18.ppppx)
        visibility = GONE
    }

    // ────────────────────────────────────────────────────────────────
    // 公开 API：host 喂作品标签
    // ────────────────────────────────────────────────────────────────

    /** 插画 / 小说（lisa 的 [TagsBean]） */
    fun setWorkTags(tags: List<TagsBean>?) {
        workTagPairs = tags.orEmpty().mapNotNull { t ->
            val name = t.name ?: return@mapNotNull null
            name to t.translated_name
        }
        // 开关在页面已打开期间被打开 → rebind 时补挂 observer（attach 时开关还是关的没挂上）
        if (isAttachedToWindow) {
            startObservingIfEnabled()
        }
        scheduleMatch()
    }

    // ────────────────────────────────────────────────────────────────
    // 生命周期：attach 时开始 observe 词典，detach 时取消
    // ────────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isInEditMode) return
        startObservingIfEnabled()
    }

    override fun onDetachedFromWindow() {
        dictLive?.removeObserver(dictObserver)
        dictLive = null
        super.onDetachedFromWindow()
    }

    /**
     * 功能总开关（issue #904）默认关闭：关闭时不 observe 词典、不渲染任何内容，
     * 普通用户的详情页和本功能存在之前完全一致。这一处 gate 覆盖全部 4 个详情页。
     * attach 和 setWorkTags 都会调用 —— 覆盖「页面打开期间用户才打开开关」的补挂场景。
     */
    private fun startObservingIfEnabled() {
        if (isInEditMode || dictLive != null) return
        if (!Shaft.sSettings.isSynonymDictEnabled) {
            visibility = GONE
            return
        }
        val live = AppDatabase.getAppDatabase(context).synonymDao().getAllWithSynonymsLive()
        dictLive = live
        live.observeForever(dictObserver)
    }

    // ────────────────────────────────────────────────────────────────
    // 匹配（后台线程）→ 渲染（主线程）
    // ────────────────────────────────────────────────────────────────

    private fun scheduleMatch() {
        // 开关关闭（含 ON→OFF 切换后 rebind 的场景）→ 清空并隐藏，即时生效
        if (!Shaft.sSettings.isSynonymDictEnabled) {
            renderSeq++
            removeAllViews()
            visibility = GONE
            return
        }
        val tags = workTagPairs
        val dict = dictionary
        renderSeq++
        val seq = renderSeq
        if (dict.isEmpty() || tags.isEmpty()) {
            removeAllViews()
            visibility = GONE
            return
        }
        matchExecutor.execute {
            // RecyclerView 快速滚动会向单线程池积压多个任务；过期任务直接跳过全量匹配计算，
            // 避免积压的任务串行空转（每个都是 O(词典大小) 的计算）。
            if (seq != renderSeq) return@execute
            val results = SynonymMatcher.match(tags, dict)
            val matched = results.filter { it.matched }
            val unmatchedCount = results.size - matched.size
            post {
                // 过期结果（输入已更新）或 view 已离屏 → 丢弃
                if (seq != renderSeq || !isAttachedToWindow) return@post
                renderResults(matched, unmatchedCount)
            }
        }
    }

    private fun renderResults(
        matched: List<SynonymMatcher.TargetResult>,
        unmatchedCount: Int,
    ) {
        removeAllViews()
        // 没有任何目标匹配该作品 → 整个框隐藏。
        // 词典大了之后几乎每个作品都"另有几千个未匹配"，常驻无信息量的提示只会占版面；
        // 词典管理入口由 设置页 / 按标签筛选页 / 匹配到时的 header 按钮兜底。
        if (matched.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        addHeader()
        // issue #910：组与组之间加一条浅分隔线，避免多个目标的 chip 看串
        matched.forEachIndexed { index, result ->
            addMatchedTarget(result, showTopDivider = index > 0)
        }
        if (unmatchedCount > 0) {
            // 末组的「另有 N 个同义词未命中」与下面这行「另有 N 个目标标签未匹配」都是灰色计数行，
            // 紧挨着会看串，中间也补一条分隔线（issue #910）
            addGroupDivider()
            addUnmatchedSummary(unmatchedCount)
        }
    }

    /** 匹配组之间的浅分隔线（issue #910）：v3_border_2 日夜自适配，浅到能分隔又不抢眼 */
    private fun addGroupDivider() {
        val h = context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        addView(View(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
                topMargin = 6.ppppx
                bottomMargin = 4.ppppx
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.v3_border_2))
        })
    }

    /** section 标题行（抄 section_v3_tags.xml 的小标题风格）+ 管理词典入口 */
    private fun addHeader() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 10.ppppx }
        }
        row.addView(TextView(context).apply {
            text = context.getString(R.string.synonym_match_relation)
            textSize = 12f
            letterSpacing = 0.12f
            isAllCaps = true
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = context.getString(R.string.synonym_manage_dict)
            textSize = 12f
            setTextColor(palette.textAccent)
            setPaddingRelative(8.ppppx, 4.ppppx, 0, 4.ppppx)
            setOnClickListener { openDictPage() }
        })
        addView(row)
    }

    /** 匹配到的目标标签：标题 + 命中 chips；未命中折叠成计数行，点击展开/再点收起（issue #905/#910） */
    private fun addMatchedTarget(result: SynonymMatcher.TargetResult, showTopDivider: Boolean) {
        if (showTopDivider) addGroupDivider()
        val synonymCount = result.matchedSynonyms.size + result.unmatchedSynonyms.size
        // 目标标签标题行
        addView(TextView(context).apply {
            text = result.target.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textAccent)
            setPaddingRelative(0, 8.ppppx, 0, 6.ppppx)
            setOnLongClickListener {
                SynonymOperate.showTargetMenu(context, result.target, synonymCount)
                true
            }
        })

        val flow = FlexboxLayout(context).apply {
            flexWrap = FlexWrap.WRAP
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 6.ppppx }
        }
        result.matchedSynonyms.forEach { item -> flow.addView(buildChip(item, matched = true)) }
        addView(flow)

        // 未命中的同义词不直接渲染（内置词典单目标可达 10+ 个，挤占版面），折叠成计数行。
        // issue #910：做成可逆开关 —— 点击在原 flow 补灰色 chip 并改为「收起」，再点移除灰 chip 还原。
        if (result.unmatchedSynonyms.isNotEmpty()) {
            val matchedChipCount = result.matchedSynonyms.size
            val toggle = TextView(context).apply {
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
                setPaddingRelative(0, 0, 0, 6.ppppx)
            }
            var expanded = false
            fun applyToggleLabel() {
                toggle.text = if (expanded) {
                    context.getString(R.string.synonym_collapse_synonyms, result.unmatchedSynonyms.size)
                } else {
                    context.getString(R.string.synonym_more_unmatched_synonyms, result.unmatchedSynonyms.size)
                }
            }
            applyToggleLabel()
            toggle.setOnClickListener {
                expanded = !expanded
                if (expanded) {
                    result.unmatchedSynonyms.forEach { item ->
                        flow.addView(buildChip(item, matched = false))
                    }
                } else {
                    // 只移除展开时追加的未命中 chip，命中 chip 原样保留
                    while (flow.childCount > matchedChipCount) {
                        flow.removeViewAt(flow.childCount - 1)
                    }
                }
                applyToggleLabel()
            }
            addView(toggle)
        }
    }

    /** 未匹配目标折叠行：「另有 N 个目标标签未匹配 ›」→ 点击进管理页 */
    private fun addUnmatchedSummary(count: Int) {
        addView(TextView(context).apply {
            text = context.getString(R.string.synonym_more_unmatched, count)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
            setPaddingRelative(0, 8.ppppx, 0, 4.ppppx)
            setOnClickListener { openDictPage() }
        })
    }

    /** 同义词 chip：命中 = accent 胶囊（同 V3TagFlowView 风格），未命中 = 灰色胶囊 */
    private fun buildChip(item: SynonymMatcher.MatchItem, matched: Boolean): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = buildString {
                append(item.synonym.name)
                if (!item.synonym.remark.isNullOrBlank()) {
                    append("  "); append(item.synonym.remark)
                }
            }
            textSize = 13f
            if (matched) {
                setTextColor(palette.textTag)
                background = palette.tagLockedBg(999f * density)
            } else {
                setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(ContextCompat.getColor(context, R.color.v3_surface_2))
                }
            }
            setPaddingRelative(14.ppppx, 7.ppppx, 14.ppppx, 7.ppppx)
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 8.ppppx, 8.ppppx)
                flexShrink = 0f
            }
            setOnLongClickListener {
                SynonymOperate.showSynonymMenu(context, item.synonym)
                true
            }
        }
    }

    private fun openDictPage() {
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "同义词词典")
        context.startActivity(intent)
    }

    companion object {
        /** 匹配计算共享单线程池：计算是纯函数 + 输入是不可变快照，单线程串行即可 */
        private val matchExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "synonym-match").apply { isDaemon = true }
        }
    }
}
