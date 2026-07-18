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
private const val TOGGLE_TAG_NAME = "___EXPAND_TOGGLE___"

// ========== DEBUG 开关 ==========
private const val DEBUG_MOCK_TAGS = false
private const val DEBUG_MOCK_TAG_COUNT = 100
// =================================

/**
 * 画师主页「插画」Tab —— 顶部标签筛选条 + 内嵌 FragmentUserIllust。
 *
 * 筛选条(issue #569)住在页面内部,跟随 ViewPager 横滑;数据复用插画列表首屏
 * (onUserIllustFirstPage 回调),进主页零额外请求。
 */
class UserV3IllustTabFragment : Fragment(), UserIllustFirstPageListener {

    private var userId = 0
    private var tagsRendered = false
    private lateinit var filterBar: V3TagFlowView
    private var isExpanded = false
    private var allTags: List<TagsBean> = emptyList()
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

        // 初始状态：不可滚动
        tagScrollContainer.isNestedScrollingEnabled = false
        // 旋转/重建时 childFragmentManager 自己恢复列表,别再 add 一份
        if (childFragmentManager.findFragmentById(R.id.illust_list_container) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.illust_list_container, UserIllustFeedFragment.newInstance(userId, false))
                .commit()
        }
    }

    /** 插画列表首屏回调:聚合高频 tag → 短到长排序 → 渲染筛选条(最多 2 行,溢出「+N」)。 */
    override fun onUserIllustFirstPage(illusts: List<IllustsBean>) {
        if (view == null || tagsRendered) return

        // ========== DEBUG: 使用模拟数据 ==========
        if (DEBUG_MOCK_TAGS) {
            allTags = generateMockTags(DEBUG_MOCK_TAG_COUNT)
            tagsRendered = true
            setupFilterBar()
            return
        }
        // ========================================

        if (illusts.isEmpty()) return

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

    private fun generateMockTags(count: Int): List<TagsBean> {
        val mockNames = listOf(
            "アニメ", "漫画", "イラスト", "ファンタジー", "SF",
            "百合", "BL", "恋愛", "コメディ", "ホラー",
            "日常", "青春", "学園", "異世界", "転生",
            "冒険", "バトル", "スポーツ", "音楽", "芸術",
            "料理", "旅行", "自然", "動物", "植物",
            "宇宙", "海洋", "都市", "田舎", "歴史",
            "文化", "伝統", "祭り", "花火", "雪",
            "桜", "紅葉", "夏", "冬", "春",
            "秋", "雨", "晴れ", "夜空", "夕日",
            "朝日", "月", "星", "雲", "虹"
        )

        val result = mutableListOf<TagsBean>()
        for (i in 0 until count) {
            val name = if (i < mockNames.size) mockNames[i] else "タグ${i + 1}"
            val bean = TagsBean().apply {
                this.name = name
                translated_name = if (i % 3 == 0) {
                    when (name) {
                        "アニメ" -> "Anime"
                        "漫画" -> "Manga"
                        "イラスト" -> "Illustration"
                        "ファンタジー" -> "Fantasy"
                        "恋愛" -> "Romance"
                        "日常" -> "Daily"
                        "異世界" -> "Isekai"
                        "冒険" -> "Adventure"
                        "宇宙" -> "Space"
                        "桜" -> "Sakura"
                        else -> "Tag${i + 1}"
                    }
                } else null
            }
            result.add(bean)
        }
        return result.sortedBy { chipVisualWidth(it) }
    }

    /**
     * 设置 filterBar（抽取公共逻辑）
     */
    private fun setupFilterBar() {
        filterBar.compact = true          // 小号 chip(11.5sp)
        filterBar.showHashPrefix = false  // 列表筛选条不带「#」前缀
        filterBar.maxTags = -1            // 先全渲染,再按行数折叠(见 trimToTwoRows)

        // 自定义 toggle tag 显示
        filterBar.customTagDisplay = customTagDisplay@{ name, translated ->
            if (name == TOGGLE_TAG_NAME) {
                return@customTagDisplay Pair(translated ?: "", null)
            }
            null
        }
        // 点击不跳搜索页,而是打开该画师按此 tag 筛选的作品页(issue #569 现有 ajax 路由)
        // 特殊处理「展开/收起」tag,点开则切换状态
        filterBar.onTagClick = { name ->
            if (name == TOGGLE_TAG_NAME) {
                toggleExpand()
            } else {
                val intent = Intent(requireContext(), TemplateActivity::class.java)
                intent.putExtra(Params.USER_ID, userId)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画标签作品")
                intent.putExtra(Params.KEY_WORD, name)
                startActivity(intent)
            }
        }
        // 初始状态：折叠
        isExpanded = false
        applyExpandState()

        filterBar.alpha = 0f
        filterBar.isVisible = true
        filterBar.animate().alpha(1f).setDuration(250).start()
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        // 展开时允许滚动，收起时禁止滚动
        tagScrollContainer.isNestedScrollingEnabled = isExpanded
        applyExpandState()
    }

    private fun applyExpandState() {
        if (isExpanded) {
            val displayList = allTags.toMutableList()
            displayList.add(createToggleTag("收起"))
            filterBar.setJavaTags(displayList)
        } else {
            val (firstTwoCount, total) = calculateTwoRowCount()
            if (total > firstTwoCount) {
                val displayList = allTags.take(firstTwoCount).toMutableList()
                displayList.add(createToggleTag("+${total - firstTwoCount}"))
                filterBar.setJavaTags(displayList)
            } else {
                filterBar.setJavaTags(allTags)
            }
        }
    }

    private fun createToggleTag(text: String): TagsBean {
        return TagsBean().apply {
            name = TOGGLE_TAG_NAME
            translated_name = text
        }
    }

    private fun calculateTwoRowCount(): Pair<Int, Int> {
        val width = view?.width ?: 0
        if (width <= 0 || allTags.isEmpty()) return Pair(allTags.size, allTags.size)

        filterBar.setJavaTags(allTags)
        filterBar.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val lines = filterBar.flexLines
        if (lines.size <= 2) {
            return Pair(allTags.size, allTags.size)
        }

        val firstTwo = lines[0].itemCount + lines[1].itemCount
        var target = (firstTwo - 1).coerceAtLeast(1)

        while (true) {
            val testList = allTags.take(target).toMutableList()
            testList.add(createToggleTag("+${allTags.size - target}"))
            filterBar.setJavaTags(testList)
            filterBar.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (filterBar.flexLines.size <= 2 || target <= 1) {
                break
            }
            target -= 1
        }

        return Pair(target, allTags.size)
    }

    /** chip 视觉宽度估算:CJK/假名占 2 格、ASCII 占 1 格;含原文 + 译文(V3TagFlowView 同 chip 显示两者)。 */
    private fun chipVisualWidth(tag: TagsBean): Int {
        fun width(s: String?): Int {
            if (s.isNullOrEmpty()) return 0
            var w = 0
            for (c in s) w += if (c.code > 0x2E7F) 2 else 1
            return w
        }
        val disp = tag.translated_name?.takeIf { it.isNotBlank() && it != TOGGLE_TAG_NAME }
        return width(tag.name) + (disp?.let { 1 + width(it) } ?: 0)
    }
}