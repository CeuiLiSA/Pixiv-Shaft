package ceui.pixiv.widgets

import android.content.Context
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Parcelable
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.children
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import ceui.lisa.R
import ceui.pixiv.ui.settings.ThemeColorCatalog
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.widget.WitTagFlowView
import ceui.pixiv.witstudio.widget.WitTagItem
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WitTagFlowViewTest {
    private fun context(dark: Boolean = false, fontScale: Float = 1f): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
    }
    private fun flow(ctx: Context = context()) = WitTagFlowView(ctx).apply {
        id = 1234
        flexWrap = FlexWrap.WRAP
        setItems(listOf(WitTagItem("a", "同名"), WitTagItem("b", "同名"), WitTagItem("c", "third")))
    }
    private fun size(view: View, width: Int = 300) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun `single multi and bounded selection stay consistent with rendered state`() {
        val flow = flow()
        flow.maxSelectCount = 1
        flow.getChildAt(0).performClick()
        flow.getChildAt(1).performClick()
        assertEquals(setOf("b"), flow.selectedKeys)
        assertFalse(flow.getChildAt(0).isActivated)
        assertTrue(flow.getChildAt(1).isActivated)
        flow.maxSelectCount = 2
        flow.getChildAt(2).performClick()
        flow.getChildAt(0).performClick()
        assertEquals(setOf("b", "c"), flow.selectedKeys)
        flow.maxSelectCount = 1
        assertEquals(setOf("b"), flow.selectedKeys)
        assertFalse(flow.getChildAt(2).isActivated)
        flow.maxSelectCount = -1
        flow.getChildAt(0).performClick()
        flow.getChildAt(2).performClick()
        assertEquals(setOf("a", "b", "c"), flow.selectedKeys)
        flow.maxSelectCount = 0
        assertTrue(flow.selectedKeys.isEmpty())
        assertTrue(flow.children.none { it.isActivated })
    }

    @Test fun `preselection filters invalid keys and follows identity after reorder and removal`() {
        val flow = WitTagFlowView(context()).apply {
            maxSelectCount = 2
            setSelectedKeys(linkedSetOf("b", "invalid", "a", "c"))
            setTagNames(listOf("a", "b", "c"))
        }
        assertEquals(setOf("a", "b"), flow.selectedKeys)
        flow.setTagNames(listOf("c", "b", "a"))
        assertFalse(flow.getChildAt(0).isActivated)
        assertTrue(flow.getChildAt(1).isActivated)
        flow.setTagNames(listOf("c", "b"))
        assertEquals(setOf("b"), flow.selectedKeys)
    }

    @Test fun `state restores before async data including empty load and explicitly empty selection`() {
        for (selected in listOf(setOf("b"), emptySet())) {
            val original = flow().apply { maxSelectCount = -1; setSelectedKeys(selected) }
            val state = SparseArray<Parcelable>()
            original.saveHierarchyState(state)
            val restored = WitTagFlowView(context()).apply {
                id = 1234
                maxSelectCount = -1
                setSelectedKeys(setOf("a"))
                restoreHierarchyState(state)
                setItems(emptyList())
                setTagNames(listOf("a", "b"))
            }
            assertEquals(selected, restored.selectedKeys)
            val rebound = flow().apply { maxSelectCount = -1; setSelectedKeys(setOf("a")) }
            rebound.restoreHierarchyState(state)
            assertEquals(selected, rebound.selectedKeys)
        }
    }

    @Test fun `independent delete and custom content children never activate parent action`() {
        val actions = mutableListOf<String>()
        val flow = flow().apply {
            setOnItemClickListener { _, _ -> actions += "body" }
            setOnItemLongClickListener { _, _ -> actions += "menu"; true }
            setOnItemRemoveListener { _, _ -> actions += "delete" }
            setItems(listOf(WitTagItem("a", "A long search history", removeDescription = "删除 A")))
        }
        val row = flow.getChildAt(0) as ViewGroup
        val delete = row.children.filterIsInstance<ImageButton>().single()
        delete.performClick()
        assertEquals(listOf("delete"), actions)
        row.performClick()
        row.performLongClick()
        assertEquals(listOf("delete", "body", "menu"), actions)
        flow.setItemViewFactory { _, _, _, _ -> LinearLayout(flow.context).apply {
            addView(TextView(context).apply { setOnClickListener { actions += "child" } })
        } }
        flow.setTagNames(listOf("avatar"))
        (flow.getChildAt(0) as ViewGroup).getChildAt(0).performClick()
        assertEquals("child", actions.last())
    }

    @Test fun `factory replacement is not invoked with the previous dataset`() {
        val flow = flow()
        flow.setItemViewFactory { _, item, _, _ ->
            assertEquals("new", item.key)
            TextView(flow.context)
        }
        flow.setTagNames(listOf("new"))
    }

    @Test fun `history touch dispatch separates body delete and long press`() {
        val actions = mutableListOf<String>()
        val flow = flow().apply {
            setOnItemClickListener { _, _ -> actions += "body" }
            setOnItemRemoveListener { _, _ -> actions += "delete" }
            setOnItemLongClickListener { _, _ -> actions += "menu"; true }
            setItems(listOf(WitTagItem("a", "历史标签", removeDescription = "删除")))
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(flow)
            shadowOf(Looper.getMainLooper()).idle()
            for (rtl in listOf(false, true)) {
                flow.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                size(flow)
                val row = flow.getChildAt(0) as ViewGroup
                val body = row.getChildAt(0)
                val delete = row.children.filterIsInstance<ImageButton>().single()
                fun gesture(target: View, longPress: Boolean = false) {
                    val downTime = SystemClock.uptimeMillis()
                    val x = row.left + target.left + target.width / 2f
                    val y = row.top + target.top + target.height / 2f
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                        flow.dispatchTouchEvent(event)
                        event.recycle()
                        if (action == MotionEvent.ACTION_DOWN && longPress) {
                            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                                ViewConfiguration.getLongPressTimeout().toLong() + 100))
                        }
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                }
                actions.clear()
                gesture(body)
                gesture(delete)
                gesture(body, longPress = true)
                assertEquals("rtl=$rtl", listOf("body", "delete", "menu"), actions)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun `identical rebinding keeps views and action changes are read at click time`() {
        val flow = flow()
        val first = flow.getChildAt(0)
        flow.setItems(listOf(WitTagItem("a", "同名"), WitTagItem("b", "同名"), WitTagItem("c", "third")))
        assertSame(first, flow.getChildAt(0))
        flow.maxTags = 1
        var clicks = 0
        flow.onOverflowClick = { clicks++ }
        val action = flow.getChildAt(1)
        flow.onOverflowClick = { clicks += 2 }
        assertSame(action, flow.getChildAt(1))
        action.performClick()
        assertEquals(2, clicks)
        flow.overflowActionText = "编辑标签"
        assertEquals("编辑标签", (flow.getChildAt(1) as TextView).text.toString())
    }

    @Test fun `theme changes update same tags and day night both use palette`() {
        for (dark in listOf(false, true)) {
            val ctx = context(dark)
            val flow = flow(ctx)
            val color = (flow.getChildAt(0) as TextView).currentTextColor
            assertEquals(V3Palette.from(ctx).textTag, color)
            ctx.setTheme(R.style.AppTheme_Index5)
            flow.refreshTheme()
            val changed = (flow.getChildAt(0) as TextView).currentTextColor
            assertEquals(V3Palette.from(ctx).textTag, changed)
            assertNotEquals(color, changed)
            flow.maxSelectCount = -1
            flow.setSelectedKeys(setOf("b"))
            savePreview(flow, "theme-$dark")
        }
    }

    @Test fun `theme tag text stays readable on normal and selected chips`() {
        val failures = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            for (theme in ThemeColorCatalog.entries) {
                val palette = V3Palette(theme.hex.toColorInt(), dark)
                // V3 表面含选择态；搜索/旧详情的标签是动作模式，使用旧版页面底色。
                val surfaces = listOf(palette.cardFill to palette.alpha08,
                    palette.cardFill to palette.alpha20,
                    (if (dark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt()) to palette.alpha08)
                for ((surface, tint) in surfaces) {
                    val background = ColorUtils.compositeColors(tint, surface)
                    val contrast = ColorUtils.calculateContrast(palette.textTag, background)
                    if (contrast < 4.5) failures += "${theme.hex}, dark=$dark: $contrast"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `long labels and independent delete fit narrow screens at large font in both directions`() {
        for (rtl in listOf(false, true)) {
            val flow = flow(context(fontScale = 2f)).apply {
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                setPaddingRelative(8, 4, 16, 4)
                setItems(listOf(WitTagItem("a", "很长的搜索标签和官方译文 repeated words repeated words"),
                    WitTagItem("b", "long history repeated words repeated words", removeDescription = "删除")))
            }
            size(flow, 220)
            flow.children.forEach {
                assertTrue("left=${it.left}", it.left >= flow.paddingLeft)
                assertTrue("right=${it.right}", it.right <= flow.width - flow.paddingRight)
                assertTrue(it.bottom <= flow.height - flow.paddingBottom)
            }
            val history = flow.getChildAt(1) as ViewGroup
            assertTrue(history.getChildAt(0).width > 0)
            assertTrue((history.getChildAt(0) as TextView).lineCount > 1)
            savePreview(flow, "large-font-rtl-$rtl", 220)
        }
    }

    @Test fun `normal tags keep a content sized pill inside the 48dp touch target`() {
        val flow = flow()
        val density = flow.resources.displayMetrics.density
        size(flow)
        val chip = flow.getChildAt(0)
        val pill = chip.background as InsetDrawable
        pill.setBounds(0, 0, chip.width, chip.height)
        val visible = pill.drawable!!.bounds.height()
        assertTrue("hit=${chip.height}", chip.height >= (48 * density).roundToInt())
        assertTrue("visible=$visible", visible < 40 * density)
        flow.compact = true
        size(flow)
        assertFalse(flow.getChildAt(0).background is InsetDrawable)
        assertTrue(flow.getChildAt(0).height < 32 * density)
    }

    @Test fun `alignment margins gone and unbounded width use the shared flexbox engine`() {
        val flow = flow().apply { setTagNames(listOf("one", "two", "three")) }
        flow.getChildAt(1).visibility = View.GONE
        flow.justifyContent = JustifyContent.CENTER
        size(flow, 500)
        assertTrue(flow.getChildAt(0).left > 0)
        assertTrue(flow.getChildAt(2).left > flow.getChildAt(0).right)
        flow.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        assertTrue(flow.measuredWidth > 0)
        flow.setItems(emptyList())
        size(flow)
        assertEquals(0, flow.measuredHeight)
    }

    private fun savePreview(flow: WitTagFlowView, name: String, width: Int = 320) {
        if (Build.VERSION.SDK_INT != 35) return
        size(flow, width)
        flow.setBackgroundColor(V3Palette.from(flow.context).cardFill)
        val bitmap = Bitmap.createBitmap(flow.width, flow.height, Bitmap.Config.ARGB_8888)
        flow.draw(Canvas(bitmap))
        val directory = File("build/reports/tag-flow").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
