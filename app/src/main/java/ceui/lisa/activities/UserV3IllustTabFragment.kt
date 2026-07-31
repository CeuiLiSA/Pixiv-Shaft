package ceui.lisa.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.pixiv.ui.user.UserIllustFeedFragment
import ceui.pixiv.widgets.V3TagFlowView

private const val ARG_USER_ID = "illust_tab_user_id"
private const val MAX_ILLUST_TAG_CHIPS = 20 // issue #569: 高频 tag 药丸最多展示数(折叠前的候选池)

/**
 * 画师主页「插画」Tab —— 顶部标签筛选条 + 内嵌 FragmentUserIllust。
 *
 * 筛选条(issue #569)住在页面内部,跟随 ViewPager 横滑;数据复用插画列表首屏
 * (onUserIllustFirstPage 回调),进主页零额外请求。
 *
 * 折叠态最多 2 行,溢出的 tag 收进可点击的「+N」块;点开全量展开、末尾带「收起」块
 * (PR #947)。展开态外层 MaxHeightNestedScrollView 封顶,tag 多时内部滚动,
 * 不把下方作品列表挤没。
 */
class UserV3IllustTabFragment : Fragment(), UserIllustFirstPageListener {

    private var userId = 0
    private var tagsRendered = false
    private var isExpanded = false
    private var allTags: List<TagsBean> = emptyList()
    // trimCollapsedToTwoRows 的收敛结果缓存(展开/收起来回切换不重复离屏测量)
    private var trimmedMaxTags = 0
    private var trimmedForWidth = 0
    private lateinit var filterBar: V3TagFlowView
    private lateinit var tagScrollContainer: NestedScrollView

    companion object {
        fun newInstance(userId: Int): UserV3IllustTabFragment =
            UserV3IllustTabFragment().apply {
                arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        userId = arguments?.getInt(ARG_USER_ID) ?: 0
        return inflater.inflate(R.layout.fragment_user_v3_illust_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filterBar = view.findViewById(R.id.illust_filter_bar)
        tagScrollContainer = view.findViewById(R.id.tag_scroll_container)
        // 隔断嵌套滚动分发:宿主是 CoordinatorLayout+AppBar,不隔断的话在展开的 tag 区里
        // 拖动会先把 AppBar 拉着收起,tag 自己反而滚不动。NestedScrollView 构造函数里
        // 强制 setNestedScrollingEnabled(true),XML 写 nestedScrollingEnabled 属性无效,
        // 只能在代码里关。
        tagScrollContainer.isNestedScrollingEnabled = false
        // 旋转/重建时 childFragmentManager 自己恢复列表,别再 add 一份
        if (childFragmentManager.findFragmentById(R.id.illust_list_container) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.illust_list_container, UserIllustFeedFragment.newInstance(userId, false))
                .commit()
        }
    }

    /** 插画列表首屏回调:聚合高频 tag → 短到长排序 → 渲染筛选条(折叠态最多 2 行,溢出「+N」)。 */
    override fun onUserIllustFirstPage(illusts: List<IllustsBean>) {
        if (view == null || tagsRendered || illusts.isEmpty()) return
        // 列表首屏已按全局设置过滤过屏蔽作品/tag,这里直接按频率聚合,保留首次出现的 TagsBean(含译名)
        val freq = LinkedHashMap<String, Int>()
        val beanOf = HashMap<String, TagsBean>()
        for (ill in illusts) {
            val tags = ill.tags ?: continue
            for (t in tags) {
                val name = t.name ?: continue
                if (name.isBlank()) continue
                freq[name] = (freq[name] ?: 0) + 1
                beanOf.getOrPut(name) { t }
            }
        }
        if (freq.isEmpty()) return
        // 先按频率取高频 Top-N,再按 chip 视觉宽度从短到长排列 —— 换行堆叠更整齐
        allTags = freq.entries
            .sortedByDescending { it.value }
            .take(MAX_ILLUST_TAG_CHIPS)
            .mapNotNull { beanOf[it.key] }
            .sortedBy { chipVisualWidth(it) }
        if (allTags.isEmpty()) return
        tagsRendered = true
        setupFilterBar()
    }

    /** 首屏 tag 就绪后一次性配置筛选条,折叠渲染完成后整体淡入。 */
    private fun setupFilterBar() {
        filterBar.compact = true          // 小号 chip(11.5sp)
        filterBar.showHashPrefix = false  // 列表筛选条不带「#」前缀
        // 点击不跳搜索页,而是打开该画师按此 tag 筛选的作品页(issue #569 现有 ajax 路由)
        filterBar.onTagClick = { name ->
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, userId)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画标签作品")
            intent.putExtra(Params.KEY_WORD, name)
            startActivity(intent)
        }
        // 「+N」/「收起」块点击 = 切换展开状态(PR #947)。溢出块是否挂点击监听在渲染时
        // 决定,所以必须先赋值再 setJavaTags。
        filterBar.onOverflowClick = { toggleExpand() }
        // 折叠必须在展示之前完成:之前是先可见渲染全量 chip 再 post 折叠,
        // 中间一两帧用户会看到七八行 tag 闪现又缩回去。FlexboxLayout 在 measure
        // 阶段就产出 flexLines,不需要真的上屏 —— 离屏量好、同步收敛,再淡入。
        applyExpandState()
        filterBar.alpha = 0f
        filterBar.isVisible = true
        filterBar.animate().alpha(1f).setDuration(250).start()
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        applyExpandState()
        if (!isExpanded) {
            // 展开态可能滚到过底部;收起后内容缩回 2 行,scrollY 显式归零,
            // 不依赖 NestedScrollView 下一次 layout 的自动 clamp。
            tagScrollContainer.scrollTo(0, 0)
        }
    }

    /**
     * 按 [isExpanded] 渲染筛选条:
     * - 收起 = 前 2 行 + 可点「+N」([trimCollapsedToTwoRows] 收敛行数);
     * - 展开 = 全量 chip + 末尾可点「收起」块,高度由外层 MaxHeightNestedScrollView 封顶。
     * 多个 setter 触发的中间渲染都在同一帧内同步完成,上屏的只有最终状态。
     */
    private fun applyExpandState() {
        if (isExpanded) {
            filterBar.maxTags = -1
            filterBar.overflowActionText = getString(R.string.v3_tag_bar_collapse)
            filterBar.setJavaTags(allTags)
        } else {
            filterBar.overflowActionText = null
            filterBar.setJavaTags(allTags)
            trimCollapsedToTwoRows()
        }
    }

    /**
     * 真·行数限制:离屏 measure 让 flexbox 自然换行,读 flexLines 数出前 2 行实际能
     * 容纳的 chip 数,超出的用一个「+N」块表达(V3TagFlowView.maxTags)。既不像 maxLine
     * 那样硬切末尾 chip,也不拍脑袋定死数量 —— 放得下几个就显示几个。
     * 全程在中间态不上屏的前提下完成(首渲染在 view 可见之前,收起切换在同一帧内)。
     * 收敛结果按宽度缓存:展开/收起来回切换不重复测量;旋转走新 Fragment 实例,缓存自然作废。
     */
    private fun trimCollapsedToTwoRows() {
        // filterBar 是 scroll 容器里的 match_parent 无 margin,宽度即 fragment 根布局宽度
        val width = (view?.width ?: 0)
        if (width <= 0) return
        if (width == trimmedForWidth) {
            filterBar.maxTags = trimmedMaxTags
            return
        }
        fun measuredLines(): Int {
            filterBar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return filterBar.flexLines.size
        }
        var target = allTags.size                         // maxTags = 总数,即不折叠
        if (measuredLines() > 2) {
            val lines = filterBar.flexLines
            val firstTwo = lines[0].itemCount + lines[1].itemCount
            target = (firstTwo - 1).coerceAtLeast(1)      // 留一个位给「+N」块
            filterBar.maxTags = target                    // setter 内部重渲染 + 追加「+N」
            // 「+N」块占位可能把第 2 行再挤出一个 → 递减收敛
            while (measuredLines() > 2 && target > 1) {
                target -= 1
                filterBar.maxTags = target
            }
        }
        trimmedForWidth = width
        trimmedMaxTags = target
    }

    /** chip 视觉宽度估算:CJK/假名占 2 格、ASCII 占 1 格;含原文 + 译文(V3TagFlowView 同 chip 显示两者)。 */
    private fun chipVisualWidth(tag: TagsBean): Int {
        fun width(s: String?): Int {
            if (s.isNullOrEmpty()) return 0
            var w = 0
            for (c in s) w += if (c.code > 0x2E7F) 2 else 1
            return w
        }
        val disp = tag.translated_name?.takeIf { it.isNotBlank() }
        return width(tag.name) + (disp?.let { 1 + width(it) } ?: 0)
    }
}
