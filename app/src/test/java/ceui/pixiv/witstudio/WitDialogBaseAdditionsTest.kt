package ceui.pixiv.witstudio

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogRootLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

/**
 * 弹窗基座这三条新能力的回归守卫（`WitDialogBuilder.addTitleAction` /
 * `MenuBaseDialogBuilder.setItemStatus` / `MenuBaseDialogBuilder.setCollapsibleHint`）。
 *
 * 它们都是往**所有**弹窗共用的那条装配链上加东西，所以重点不是「用了能显示」，而是
 * **「没用的时候必须跟从前一模一样」** —— 全仓一百多处弹窗调用点一行都没改，
 * 基座一旦多出可见节点、多出留白、把标题挤歪，它们全都跟着变。这里的用例就是钉住这条。
 *
 * 布局断言按 density 换算而不是写死 dp 数字：Robolectric 默认 mdpi（dp == px），
 * 写死的期望值会把错误的实现一起放过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class WitDialogBaseAdditionsTest {

    private lateinit var controller: ActivityController<FragmentActivity>

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
    }

    @After
    fun tearDown() {
        if (!::controller.isInitialized) return
        (ShadowDialog.getLatestDialog() as? AppCompatDialog)?.takeIf { it.isShowing }?.dismiss()
        controller.pause().stop().destroy()
    }

    // ── 标题右侧图标动作 ────────────────────────────────────────────

    @Test
    fun `没设图标动作时标题仍是纯文本 不多出任何图标`() {
        val dialog = WitDialog.MessageDialogBuilder(activity())
            .setTitle("标题")
            .setMessage("正文")
            .create()
        dialog.show()

        val titleView = dialog.titleView()
        // 老调用点的契约：标题就是那个 TextView 自己，没有被包进任何容器。
        assertTrue("标题应仍是 TextView 本体", titleView is TextView)
        assertEquals("标题", (titleView as TextView).text.toString())
        assertTrue(
            "没有 title action 时不该出现任何图标",
            descendants(dialog).filterIsInstance<ImageView>().isEmpty(),
        )
        assertEquals(listOf("标题", "正文"), visibleLabels(dialog))
    }

    @Test
    fun `设了图标动作时标题仍可见 图标可点且带无障碍描述`() {
        var clicks = 0
        val dialog = WitDialog.MessageDialogBuilder(activity())
            .setTitle("github加速地址")
            .addTitleAction(R.drawable.ic_setcat_globe, "网络测试") { clicks++ }
            .setMessage("正文")
            .create()
        dialog.show()

        assertEquals(listOf("github加速地址", "正文"), visibleLabels(dialog))

        val icon = actionIcon(dialog, "网络测试")
        assertNotNull("应能找到带描述的图标按钮", icon)
        assertEquals("网络测试", icon!!.contentDescription.toString())
        icon.performClick()
        icon.performClick()
        assertEquals("点击必须打到调用方给的 listener 上", 2, clicks)
    }

    /**
     * 标题栏可以挂多个动作（「网络测试」+「?」）：按调用顺序从左到右排，
     * 且**最右边那个**的图形右缘仍与标题文字左缘对称于同一条 24dp 栏距 ——
     * 这正是 `wrapTitleWithActions` 里「容器只留 24dp 栏距 − 内衬、标题自己再让开图标总宽」的推导结果。
     */
    @Test
    fun `多个图标按顺序排在标题右侧且右缘仍对齐 24dp 栏距`() {
        val clicks = mutableListOf<String>()
        val dialog = WitDialog.MessageDialogBuilder(activity())
            .setTitle("github加速地址")
            .addTitleAction(R.drawable.ic_setcat_globe, "网络测试") { clicks.add("测试") }
            .addTitleAction(R.drawable.ic_help_outline_black_24dp, "说明") { clicks.add("说明") }
            .setMessage("正文")
            .create()
        dialog.show()

        val container = dialog.titleView() as FrameLayout
        val width = dp(300)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.AT_MOST),
        )
        container.layout(0, 0, width, container.measuredHeight)

        val title = container.getChildAt(0)
        val row = container.getChildAt(1) as ViewGroup
        val gutter = dp(24)
        assertEquals("两个动作都要在", 2, row.childCount)

        val first = row.getChildAt(0)
        val last = row.getChildAt(1)
        assertTrue("先加的在左边", first.left < last.left)

        val lastGraphicRight = row.left + last.right - last.paddingRight
        assertEquals("最右图标的图形右缘应落在 24dp 栏距上", width - gutter, lastGraphicRight)
        assertEquals("标题文字左缘同样 24dp", gutter, title.left + title.paddingStart)
        // 标题的文字区必须整体停在图标左边，长标题才不会钻到图标底下。
        assertTrue("标题右内边距应为图标总宽", title.paddingEnd >= row.width)

        actionIcon(dialog, "网络测试")!!.performClick()
        actionIcon(dialog, "说明")!!.performClick()
        assertEquals(listOf("测试", "说明"), clicks)
    }

    /**
     * 单个图标时同样要跟标题共用**同一条 24dp 栏距**：图形（不是 40dp 热区）的右缘距容器右缘 24dp，
     * 与标题文字左缘距容器左缘的 24dp 对称；纵向居中也不被标题原本的顶部留白顶偏。
     *
     * 这三个数是 `wrapTitleWithActions` 里「把标题的纵向与右侧内边距上移到容器」的推导结果，
     * 谁只改一边而没同步改另一边，这里会红。
     */
    @Test
    fun `图标图形右缘与标题文字左缘落在同一条 24dp 栏距上`() {
        val dialog = WitDialog.MessageDialogBuilder(activity())
            .setTitle("标题")
            .addTitleAction(R.drawable.ic_setcat_globe, "网络测试", null)
            .setMessage("正文")
            .create()
        dialog.show()

        val container = dialog.titleView() as FrameLayout
        val width = dp(300)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.AT_MOST),
        )
        container.layout(0, 0, width, container.measuredHeight)

        val title = container.getChildAt(0)
        // 图标挂在容器右侧的一行里，坐标是相对那一行的，比较前要加上行的左偏移。
        val row = container.getChildAt(1) as ViewGroup
        val icon = actionIcon(dialog, "网络测试")!!
        val gutter = dp(24)

        assertEquals("标题文字左缘应留 24dp 栏距", gutter, title.left + title.paddingStart)
        assertEquals(
            "图标图形右缘应与左侧栏距对称",
            width - gutter,
            row.left + icon.right - icon.paddingRight,
        )
        assertEquals("标题自己的顶部留白应已上移到容器", 0, title.paddingTop)
        assertEquals("纵向居中靠容器的顶部留白", gutter, container.paddingTop)
    }

    // ── 菜单行副标题 ────────────────────────────────────────────────

    @Test
    fun `create 之后挂副标题 行里多一行文字 收起后回到单行`() {
        val builder = WitDialog.CheckableDialogBuilder(activity())
            .setTitle("github加速地址")
            .setCheckedIndex(1)
        builder.addItems(arrayOf("不使用", "gh-proxy.com", "自定义地址"), null)
        val dialog = builder.create()
        dialog.show()

        assertEquals(
            listOf("github加速地址", "不使用", "gh-proxy.com", "自定义地址"),
            visibleLabels(dialog),
        )

        builder.setItemStatus(1, "可达 · 128ms")
        assertTrue("副标题应出现在弹窗里", descendantLabels(dialog).contains("可达 · 128ms"))
        assertEquals("副标题只能挂在自己的那一行上", 1, rowsWithVisibleText(dialog, "可达 · 128ms"))

        val row = menuRows(dialog)[1]
        builder.setItemStatus(1, null)
        assertTrue("收起后文字必须消失", !descendantLabels(dialog).contains("可达 · 128ms"))
        assertEquals("收起后该行回到单行", listOf("gh-proxy.com"), visibleLabels(row))
    }

    @Test
    fun `副标题用的是调用方给的颜色`() {
        val builder = WitDialog.CheckableDialogBuilder(activity()).setTitle("标题")
        builder.addItems(arrayOf("甲", "乙"), null)
        val dialog = builder.create()
        dialog.show()

        val green = activity().getColor(R.color.v3_green)
        builder.setItemStatus(0, "可达", green)
        val status = visibleTextViews(menuRows(dialog)[0]).first { it.text.toString() == "可达" }
        assertEquals(green, status.currentTextColor)
    }

    /**
     * 行视图是在 `onCreateContent`（即 `create()`）里才建出来的，所以 `create()` 之前调用
     * 是**静默无效**而不是崩溃 —— 这条写进了公开 KDoc。若哪天改成「先缓存、create 时补套用」，
     * 这条用例该跟着改。
     */
    @Test
    fun `create 之前挂副标题是静默无效而不是崩溃`() {
        val builder = WitDialog.CheckableDialogBuilder(activity()).setTitle("标题")
        builder.addItems(arrayOf("甲", "乙"), null)
        builder.setItemStatus(0, "先挂上")

        val dialog = builder.create()
        dialog.show()

        assertEquals(listOf("标题", "甲", "乙"), visibleLabels(dialog))
    }

    // ── 可折叠说明行（标题栏问号图标点开的那段） ────────────────────

    @Test
    fun `没设说明时菜单内容与从前一致`() {
        val builder = WitDialog.CheckableDialogBuilder(activity()).setTitle("标题")
        builder.addItems(arrayOf("甲", "乙"), null)
        val dialog = builder.create()
        dialog.show()

        assertFalse(builder.isHintExpanded)
        assertEquals(listOf("标题", "甲", "乙"), visibleLabels(dialog))
    }

    @Test
    fun `说明默认收起 点开后出现 再点收起`() {
        val builder = WitDialog.CheckableDialogBuilder(activity()).setTitle("标题")
        builder.addItems(arrayOf("甲", "乙"), null)
        builder.setCollapsibleHint("加速从 GitHub 拉取的资源，不影响其他网络请求")
        val dialog = builder.create()
        dialog.show()

        assertFalse("默认必须收起", builder.isHintExpanded)
        assertEquals("收起时不该出现在可见文字里", listOf("标题", "甲", "乙"), visibleLabels(dialog))

        builder.setHintExpanded(true)
        assertTrue(builder.isHintExpanded)
        assertEquals(
            "展开后说明顶在菜单之上",
            listOf("标题", "加速从 GitHub 拉取的资源，不影响其他网络请求", "甲", "乙"),
            visibleLabels(dialog),
        )

        builder.setHintExpanded(false)
        assertFalse(builder.isHintExpanded)
        assertEquals(listOf("标题", "甲", "乙"), visibleLabels(dialog))
    }

    /** 空串说明 = 整行不存在：否则「设了但没内容」会留下一条空白留白。 */
    @Test
    fun `空说明不占位 展开也无效`() {
        val builder = WitDialog.CheckableDialogBuilder(activity()).setTitle("标题")
        builder.addItems(arrayOf("甲"), null)
        builder.setCollapsibleHint("")
        val dialog = builder.create()
        dialog.show()

        builder.setHintExpanded(true)
        assertFalse(builder.isHintExpanded)
        assertEquals(listOf("标题", "甲"), visibleLabels(dialog))
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private fun activity(): FragmentActivity = controller.get()

    private fun dp(value: Int): Int =
        (value * activity().resources.displayMetrics.density).roundToInt()

    /** 标题视图 = 弹窗主体（`WitDialogView`）的第一个子 View：没动作时是 TextView，有动作时是容器。 */
    private fun WitDialog.titleView(): View {
        val root = descendants(this).filterIsInstance<WitDialogRootLayout>().first()
        return root.dialogView.getChildAt(0)
    }

    private fun actionIcon(dialog: WitDialog, description: String): ImageView? =
        descendants(dialog).filterIsInstance<ImageView>()
            .firstOrNull { it.contentDescription == description }

    /** 菜单行 = 菜单内容列（`NestedScrollView → LinearLayout`）里的直接子项。 */
    private fun menuRows(dialog: WitDialog): List<View> {
        val scroll = descendants(dialog).filterIsInstance<NestedScrollView>().first()
        val column = scroll.getChildAt(0) as ViewGroup
        return (0 until column.childCount).map { column.getChildAt(it) }
    }

    private fun descendants(dialog: WitDialog): List<View> =
        descendants(dialog.window!!.decorView)

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        } else {
            emptyList()
        }

    private fun descendantLabels(dialog: WitDialog): List<String> =
        descendants(dialog).filterIsInstance<TextView>().map { it.text.toString() }

    /** 弹窗里**可见且非空**的文字，按视图树顺序。新节点在默认态下既不可见也不占位，就落不进这个列表。 */
    private fun visibleLabels(dialog: WitDialog): List<String> =
        visibleTextViews(dialog.window!!.decorView).map { it.text.toString() }.filter { it.isNotEmpty() }

    private fun visibleLabels(view: View): List<String> =
        visibleTextViews(view).map { it.text.toString() }.filter { it.isNotEmpty() }

    private fun visibleTextViews(view: View): List<TextView> =
        descendants(view).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }

    private fun rowsWithVisibleText(dialog: WitDialog, text: String): Int =
        menuRows(dialog).count { visibleLabels(it).contains(text) }
}
