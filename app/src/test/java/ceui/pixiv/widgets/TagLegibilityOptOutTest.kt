package ceui.pixiv.widgets

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.V3TagLegibility
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 坐在**宿主自备浅色面**上的标签流必须能退出「标签原文亮暗度」增强。
 *
 * 增强的目标值是按「页面底 + 自身 20% 染色填充」标定的（深色下把原文提亮）。搜索栏的输入区
 * 不满足这个前提：它是硬编码纯白胶囊（`search_et_bg`，无夜间变体），深色下提亮只会把原文推糊
 * —— 实测输入文字对白底的 APCA 对比度从 2.73 掉到 1.44。所以 `SearchActivity` 把
 * `followTagLegibilityBoost` 置 false。
 *
 * 钉两件事：关掉后**原文与输入文字**都回到「增强功能存在之前」的颜色；开着时确实跟随
 * （否则"关掉没变化"可能只是因为功能压根没生效）。
 *
 * [V3TagLegibility] 是进程级单例，`V3Palette.from()` 会读它，用例结束必须复位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class TagLegibilityOptOutTest {

    private class Rendered(val flow: V3TagFlowView, val context: Context)

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        // 两条都拉满：深浅两种模式都该看得出差异，断言不会因为"恰好落在 0 档"而空转。
        V3TagLegibility.setBoosts(1f, 1f)
    }

    @After
    fun tearDown() {
        V3TagLegibility.setBoosts(0f, 0f)
    }

    private fun render(follow: Boolean): Rendered {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val themed = ContextThemeWrapper(host, R.style.AppTheme)
        val flow = V3TagFlowView(themed).apply {
            showRemoveIcon = true
            followTagLegibilityBoost = follow
            setTagNames(listOf("初音ミク"))
        }
        host.setContentView(flow)
        shadowOf(Looper.getMainLooper()).idle()
        return Rendered(flow, themed)
    }

    /** 期望色由与视图**同一个** Context 推导，避免把主题色 / 深浅写死在用例里。 */
    private fun expected(context: Context, boost: Float): Int {
        val global = V3Palette.from(context)
        return V3Palette(global.primary, global.isDark, boost).textTag
    }

    private fun chip(flow: V3TagFlowView): TextView = flow.getChildAt(0) as TextView

    @Test
    fun `关闭后原文与输入文字都回到未增强的颜色`() {
        val rendered = render(follow = false)
        val off = expected(rendered.context, 0f)
        assertEquals(off, chip(rendered.flow).currentTextColor)
        assertEquals(off, rendered.flow.editor!!.currentTextColor)
    }

    @Test
    fun `默认仍跟随增强`() {
        val rendered = render(follow = true)
        val on = expected(rendered.context, 1f)
        assertEquals(on, chip(rendered.flow).currentTextColor)
        assertEquals(on, rendered.flow.editor!!.currentTextColor)
    }

    @Test
    fun `开关两端必须真的不同 —— 否则这条约束是空转`() {
        val rendered = render(follow = true)
        assertNotEquals(expected(rendered.context, 0f), expected(rendered.context, 1f))
    }

    @Test
    @Config(sdk = [28, 35], qualifiers = "night")
    fun `夜间同样回到未增强的颜色`() {
        val rendered = render(follow = false)
        assertTrue("night qualifier 未生效，这条用例没有覆盖深色分支", V3Palette.from(rendered.context).isDark)
        val off = expected(rendered.context, 0f)
        assertEquals(off, chip(rendered.flow).currentTextColor)
        assertEquals(off, rendered.flow.editor!!.currentTextColor)
    }
}
