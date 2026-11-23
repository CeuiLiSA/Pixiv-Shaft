package ceui.lisa.view

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.PagerAdapter
import ceui.lisa.activities.Shaft
import ceui.lisa.helper.DrawerLayoutHelper
import ceui.lisa.utils.Settings
import com.blankj.utilcode.util.Utils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class DrawerLayoutViewPagerTest {

    private var savedSettings: Settings? = null
    private var activityController: ActivityController<Activity>? = null
    private var currentFixture: Fixture? = null

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        savedSettings = Shaft.sSettings
        Shaft.sSettings = Settings()
    }

    @After
    fun tearDown() {
        currentFixture = null
        activityController?.pause()?.stop()?.destroy()
        activityController = null
        Shaft.sSettings = savedSettings
    }

    @Test
    fun `negative screen pure fling on leftmost page opens drawer and cancels child click`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        var childClicked = false
        var childReceivedCancel = false
        val context = RuntimeEnvironment.getApplication()
        val clickableChild =
            object : View(context) {
                init {
                    isClickable = true
                    setOnClickListener { childClicked = true }
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        childReceivedCancel = true
                    }
                    return super.onTouchEvent(event)
                }
            }
        val fixture = createFixture(page0 = clickableChild)

        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 400f, 500f),
                Point(12L, 520f, 505f),
                Point(24L, 680f, 510f),
                Point(36L, 840f, 512f),
            ),
        )
        idle()

        assertEquals(1, fixture.openDrawerCalls)
        assertTrue(fixture.drawer.isDrawerOpen(GravityCompat.START))
        assertFalse("Pure fling should cancel child press rather than clicking it", childClicked)
        assertTrue("Child should receive ACTION_CANCEL on drawer open", childReceivedCancel)
    }

    @Test
    fun `negative screen pure fling on non-clickable background is not aborted by synthetic cancel`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val passivePage = View(context) // does not consume ACTION_DOWN -> ViewPager handles touch itself
        val fixture = createFixture(page0 = passivePage)

        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 400f, 500f),
                Point(12L, 540f, 500f),
                Point(24L, 700f, 500f),
                Point(36L, 860f, 500f),
            ),
        )
        idle()

        assertEquals(1, fixture.openDrawerCalls)
        assertTrue(
            "Drawer open animation must survive synthetic ACTION_CANCEL when pager is touch target",
            fixture.drawer.isDrawerOpen(GravityCompat.START),
        )
    }

    @Test
    fun `negative screen ignores slow drag and braked drag without DrawerLayout hijacking`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val page0 = TouchConsumingView(context)
        val fixture = createFixture(page0 = page0)

        // 1. 慢速右拖（速度远低于 4x minFlingVelocity）
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 400f, 500f),
                Point(400L, 470f, 500f),
                Point(800L, 540f, 500f),
                Point(1200L, 600f, 500f),
            ),
        )
        idle()
        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))

        // 2. 先快甩后明显减速刹车（endVx < 0.65 * peakVx）
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 300f, 500f),
                Point(15L, 520f, 500f),
                Point(30L, 740f, 500f),
                Point(130L, 760f, 500f),
                Point(230L, 770f, 500f),
            ),
        )
        idle()
        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))
    }

    @Test
    fun `negative screen allows leftward tab swipe and vertical scroll with horizontal drift`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val verticalChild = VerticalScrollChild(context)
        val fixture = createFixture(page0 = verticalChild)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        // 1. 竖向滚动中途横向漂移超过 touchSlop，且子 View 声明 disallowIntercept：不应被 DrawerLayout 抢断
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 500f, 800f),
                Point(20L, 500f, 800f - touchSlop * 2f),
                Point(40L, 500f + touchSlop * 2.5f, 800f - touchSlop * 8f),
                Point(60L, 500f + touchSlop * 3f, 800f - touchSlop * 14f),
            ),
        )
        idle()
        assertFalse("Vertical scroll must not be cancelled by DrawerLayout", verticalChild.cancelled)
        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))

        // 2. 在第 0 页中间向左滑动切换 Tab：不应被全屏 mEdgeSize 的 DrawerLayout 劫持
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 700f, 500f),
                Point(15L, 580f, 500f),
                Point(30L, 420f, 500f),
                Point(45L, 240f, 500f),
            ),
        )
        idle()
        assertEquals(1, fixture.pager.currentItem)
        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))
    }

    @Test
    fun `negative screen does not trigger if child could scroll left at ACTION_DOWN even after hitting boundary`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val dynamicChild = DynamicHorizontalScrollChild(context, initiallyCanScrollLeft = true)
        val fixture = createFixture(page0 = dynamicChild)

        // 模拟二级 ViewPager 在第 1 页或横向货架已右滑，快甩返回途中触达最左边界（canScrollLeft 翻为 false）
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 400f, 500f, 0)
        val move1 = MotionEvent.obtain(0L, 12L, MotionEvent.ACTION_MOVE, 560f, 500f, 0)
        val move2 = MotionEvent.obtain(0L, 24L, MotionEvent.ACTION_MOVE, 740f, 500f, 0)
        val up = MotionEvent.obtain(0L, 36L, MotionEvent.ACTION_UP, 900f, 500f, 0)
        try {
            fixture.drawer.dispatchTouchEvent(down)
            fixture.drawer.dispatchTouchEvent(move1)
            dynamicChild.canScrollLeft = false
            fixture.drawer.dispatchTouchEvent(move2)
            fixture.drawer.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            move1.recycle()
            move2.recycle()
            up.recycle()
        }
        idle()

        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))
    }

    @Test
    fun `negative screen disqualifies gesture after leftward move or second pointer down`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val fixture = createFixture(page0 = TouchConsumingView(context))
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        // 1. 先明显左滑再反手右甩
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 500f, 500f),
                Point(15L, 500f - touchSlop * 2f, 500f),
                Point(30L, 640f, 500f),
                Point(45L, 840f, 500f),
            ),
        )
        idle()
        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))

        // 2. 多指交接：指 0 在左侧按下、指 1 在右侧按下、指 0 抬起后指 1 轻移抬起
        val p0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val p1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val c0 = MotionEvent.PointerCoords().apply { x = 200f; y = 500f; pressure = 1f; size = 1f }
        val c1 = MotionEvent.PointerCoords().apply { x = 750f; y = 500f; pressure = 1f; size = 1f }

        val down0 = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 500f, 0)
        val down1 = MotionEvent.obtain(
            0L,
            10L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            arrayOf(p0, p1),
            arrayOf(c0, c1),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
        val up0 = MotionEvent.obtain(
            0L,
            20L,
            MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            arrayOf(p0, p1),
            arrayOf(c0, c1),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
        val move1 = MotionEvent.obtain(0L, 30L, MotionEvent.ACTION_MOVE, 790f, 500f, 0)
        val up1 = MotionEvent.obtain(0L, 40L, MotionEvent.ACTION_UP, 840f, 500f, 0)
        try {
            fixture.drawer.dispatchTouchEvent(down0)
            fixture.drawer.dispatchTouchEvent(down1)
            fixture.drawer.dispatchTouchEvent(up0)
            fixture.drawer.dispatchTouchEvent(move1)
            fixture.drawer.dispatchTouchEvent(up1)
        } finally {
            down0.recycle()
            down1.recycle()
            up0.recycle()
            move1.recycle()
            up1.recycle()
        }
        idle()

        assertEquals(0, fixture.openDrawerCalls)
        assertFalse(fixture.drawer.isDrawerOpen(GravityCompat.START))
    }

    @Test
    fun `left edge drag opens drawer on non-zero tab when negative screen is enabled`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(true)
        val context = RuntimeEnvironment.getApplication()
        val fixture = createFixture(page0 = View(context), page1 = View(context))
        fixture.pager.setCurrentItem(1, false)

        // 从屏幕最左缘 (< leftThreshold) 向右拖拽打开抽屉
        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 16f, 500f),
                Point(15L, 160f, 500f),
                Point(30L, 380f, 500f),
                Point(45L, 620f, 500f),
            ),
        )
        idle()

        assertTrue(
            "Left edge drag on non-zero tab should still open drawer",
            fixture.drawer.isDrawerOpen(GravityCompat.START),
        )
    }

    @Test
    fun `disabled negative screen preserves left edge drawer drag without ACTION_CANCEL self-abort`() {
        Shaft.sSettings.setDrawerNegativeScreenEnabled(false)
        val context = RuntimeEnvironment.getApplication()
        // 非可点子视图：DrawerLayoutViewPager 自身作为触摸目标，DrawerLayout 拦截时会向其派发 ACTION_CANCEL
        val fixture = createFixture(page0 = View(context))

        dispatchSwipe(
            fixture.drawer,
            listOf(
                Point(0L, 16f, 500f),
                Point(15L, 180f, 500f),
                Point(30L, 420f, 500f),
                Point(45L, 660f, 500f),
            ),
        )
        idle()

        assertTrue(
            "Default left-edge drag must not be cancelled when DrawerLayout dispatches ACTION_CANCEL to pager",
            fixture.drawer.isDrawerOpen(GravityCompat.START),
        )
    }

    // ---- helpers ----------------------------------------------------------

    private data class Point(val timeMs: Long, val x: Float, val y: Float)

    private class Fixture(
        val drawer: DrawerLayout,
        val pager: DrawerLayoutViewPager,
    ) {
        var openDrawerCalls = 0
    }

    private fun createFixture(
        page0: View,
        page1: View = View(page0.context),
    ): Fixture {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        activityController = controller
        val context = controller.get()
        val drawer = DrawerLayout(context)
        val contentHost = FrameLayout(context)
        val pager = DrawerLayoutViewPager(context)
        val pages = listOf(page0, page1)
        pager.adapter =
            object : PagerAdapter() {
                override fun getCount(): Int = pages.size

                override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

                override fun instantiateItem(container: ViewGroup, position: Int): Any {
                    val page = pages[position]
                    container.addView(
                        page,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    return page
                }

                override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                    container.removeView(obj as View)
                }
            }
        contentHost.addView(
            pager,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        drawer.addView(
            contentHost,
            DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val drawerPanel = View(context)
        drawer.addView(
            drawerPanel,
            DrawerLayout.LayoutParams(DRAWER_WIDTH, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
        )

        val fixture = Fixture(drawer, pager)
        pager.setTouchEventForwarder(
            object : DrawerLayoutViewPager.IForwardTouchEvent {
                override fun forwardTouchEvent(ev: MotionEvent) {
                    drawer.onTouchEvent(ev)
                }

                override fun onOpenDrawer() {
                    fixture.openDrawerCalls++
                    drawer.openDrawer(GravityCompat.START, true)
                }
            },
        )
        DrawerLayoutHelper.setCustomLeftEdgeSize(drawer, 1.0f)

        context.setContentView(drawer)
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        drawer.layout(0, 0, WIDTH, HEIGHT)
        currentFixture = fixture
        return fixture
    }

    private fun dispatchSwipe(target: View, points: List<Point>) {
        require(points.size >= 2)
        val downTime = points.first().timeMs
        points.forEachIndexed { index, p ->
            val action =
                when (index) {
                    0 -> MotionEvent.ACTION_DOWN
                    points.lastIndex -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val ev = MotionEvent.obtain(downTime, p.timeMs, action, p.x, p.y, 0)
            try {
                target.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
    }

    private fun idle() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        repeat(40) {
            mainLooper.idleFor(Duration.ofMillis(20))
            currentFixture?.drawer?.computeScroll()
            currentFixture?.pager?.computeScroll()
        }
    }

    private open class TouchConsumingView(context: Context) : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private class VerticalScrollChild(context: Context) : View(context) {
        var cancelled = false
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    cancelled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.y - downY) > slop) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelled = true
                }
            }
            return true
        }
    }

    private class DynamicHorizontalScrollChild(
        context: Context,
        initiallyCanScrollLeft: Boolean,
    ) : View(context) {
        var canScrollLeft = initiallyCanScrollLeft

        override fun canScrollHorizontally(direction: Int): Boolean =
            if (direction < 0) canScrollLeft else false

        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val DRAWER_WIDTH = 900
    }
}
