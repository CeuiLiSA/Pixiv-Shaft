package ceui.lisa.view

import android.app.Application
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 宽栏夹高度（[DynamicHeightImageView] 的 fitPortraitInViewport）只在「平板适配排版」开启时成立，
 * 所以宿主窗口必须真的是平板尺寸：qualifiers 带 sw600dp，并在每个用例里显式给出设置开关。
 * 手机（含 1080×1920 @240dpi 这种栏宽 720dp 的低密度机）关掉平板排版后一律按宽铺满，见
 * [tablet layout off keeps natural height on a wide pane]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sw600dp-mdpi", application = Application::class)
class PortraitViewportTest {
    @Test
    fun `portrait fits actual viewport and follows fold and height changes`() = withImages { list, base, hd ->
        layout(list, 800, 900)
        assertEquals(900, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
        layout(list, 400, 900) // folded/narrow pane keeps natural height
        assertEquals(600, base.measuredHeight)
        layout(list, 800, 700) // unfold into a shorter window
        assertEquals(700, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
        layout(list, 800, 1000) // height-only resize must also remeasure
        assertEquals(1000, base.measuredHeight)
    }

    @Test
    fun `long strips landscape and non opted in images retain natural height`() = withImages { list, base, hd ->
        for (image in listOf(base, hd)) image.setHeightRatio(3f)
        layout(list, 800, 900)
        assertEquals(2400, base.measuredHeight)
        for (image in listOf(base, hd)) image.setHeightRatio(0.5f)
        layout(list, 800, 900)
        assertEquals(400, base.measuredHeight)
        for (image in listOf(base, hd)) {
            image.setHeightRatio(1.5f)
            image.setFitPortraitInViewport(false) // recycled holder rebound to manga
        }
        layout(list, 800, 900)
        assertEquals(1200, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
    }

    @Test
    fun `wide pane and long strip boundaries are explicit`() = withImages { list, base, hd ->
        layout(list, 599, 700)
        assertEquals(898, base.measuredHeight)
        layout(list, 600, 700)
        assertEquals(700, base.measuredHeight)
        for (image in listOf(base, hd)) image.setHeightRatio(2.5f)
        layout(list, 600, 700)
        assertEquals(1500, base.measuredHeight)
    }

    @Test
    fun `original ratio correction stays inside viewport`() = withImages { list, base, hd ->
        layout(list, 800, 900)
        for (image in listOf(base, hd)) image.setHeightRatio(1.8f)
        layout(list, 800, 900)
        assertEquals(900, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
    }

    @Test
    fun `cached page uses current viewport after resizing while offscreen`() = withImages(pageCount = 3) { list, base, hd ->
        layout(list, 800, 900)
        assertEquals(900, base.measuredHeight)
        list.scrollToPosition(2)
        layout(list, 800, 900)
        assertFalse(base.isAttachedToWindow)

        layout(list, 800, 700)
        list.scrollToPosition(0)
        layout(list, 800, 700)
        assertTrue(base.isAttachedToWindow)
        assertEquals(700, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
    }

    @Test
    fun `inset padding changes update available viewport`() = withImages { list, base, hd ->
        layout(list, 800, 900)
        list.setPadding(0, 0, 0, 100)
        layout(list, 800, 900)
        assertEquals(800, base.measuredHeight)
        assertEquals(base.measuredHeight, hd.measuredHeight)
    }

    /**
     * 回归：1080×1920 @240dpi 的低密度手机，栏宽 720dp 同样过 600dp 的「宽栏」线，但平板排版没开时
     * 它是手机形态 —— 图必须按宽铺满。此前这里会把高度夹到视口高，FIT_CENTER 再把宽度缩成
     * 「视口高 / 高宽比」，左右露出占位底色（用户反馈的白边）。实测图 843×1866，高宽比 2.2135。
     */
    @Test
    fun `tablet layout off keeps natural height on a wide pane`() =
        withImages(ratio = 1866f / 843f, tabletLayout = false) { list, base, hd ->
            val naturalHeight = (1080 * (1866f / 843f)).toInt()
            layout(list, 1080, 1920)
            assertEquals(naturalHeight, base.measuredHeight)
            assertEquals(base.measuredHeight, hd.measuredHeight)

            // 同一块栏、同一张图：打开平板排版才按视口夹高度，这是真平板双栏的完整显示。
            // 尺寸没变，RecyclerView 会直接复用上一轮的测量结果，必须先让子 View 标脏才会重测。
            Shaft.sSettings.isTabletLayout = true
            for (image in listOf(base, hd)) image.requestLayout()
            layout(list, 1080, 1920)
            assertEquals(1920, base.measuredHeight)
            assertEquals(base.measuredHeight, hd.measuredHeight)
        }

    private fun layout(list: RecyclerView, width: Int, height: Int) {
        // Keep the host's subsequent traversal from restoring its default 320dp window.
        list.layoutParams = list.layoutParams.apply {
            this.width = width
            this.height = height
        }
        // The viewport's first layout/resize schedules a second measurement of its images.
        repeat(2) {
            list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun withImages(
        pageCount: Int = 1,
        ratio: Float = 1.5f,
        tabletLayout: Boolean = true,
        check: (RecyclerView, DynamicHeightImageView, DynamicHeightImageView) -> Unit,
    ) {
        val oldSettings = Shaft.sSettings
        Shaft.sSettings = Settings().apply { setTabletLayout(tabletLayout) }
        val host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        host.setup()
        try {
            val activity = host.get()
            val frame = LayoutInflater.from(activity).inflate(R.layout.recy_illust_detail, null, false)
            val base = frame.findViewById<DynamicHeightImageView>(R.id.illust)
            val hd = frame.findViewById<DynamicHeightImageView>(R.id.illust_hd)
            for (image in listOf(base, hd)) {
                image.setHeightRatio(ratio)
                image.setFitPortraitInViewport(true)
                image.visibility = View.VISIBLE
            }
            val list = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    private var firstCreated = false
                    override fun getItemCount() = pageCount
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val item = if (!firstCreated) {
                            firstCreated = true
                            frame
                        } else {
                            LayoutInflater.from(activity).inflate(R.layout.recy_illust_detail, parent, false).apply {
                                for (id in listOf(R.id.illust, R.id.illust_hd)) {
                                    findViewById<DynamicHeightImageView>(id).apply {
                                        setHeightRatio(ratio)
                                        setFitPortraitInViewport(true)
                                        visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                        item.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                        return object : RecyclerView.ViewHolder(item) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
            }
            activity.setContentView(list)
            check(list, base, hd)
        } finally {
            host.pause().stop().destroy()
            Shaft.sSettings = oldSettings
        }
    }
}
