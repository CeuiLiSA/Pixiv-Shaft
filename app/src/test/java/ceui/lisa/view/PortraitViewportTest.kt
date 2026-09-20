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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
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

    private fun withImages(check: (RecyclerView, DynamicHeightImageView, DynamicHeightImageView) -> Unit) {
        val host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        host.setup()
        try {
            val activity = host.get()
            val frame = LayoutInflater.from(activity).inflate(R.layout.recy_illust_detail, null, false)
            val base = frame.findViewById<DynamicHeightImageView>(R.id.illust)
            val hd = frame.findViewById<DynamicHeightImageView>(R.id.illust_hd)
            for (image in listOf(base, hd)) {
                image.setHeightRatio(1.5f)
                image.setFitPortraitInViewport(true)
                image.visibility = View.VISIBLE
            }
            val list = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 1
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        frame.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                        return object : RecyclerView.ViewHolder(frame) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
            }
            activity.setContentView(list)
            check(list, base, hd)
        } finally {
            host.pause().stop().destroy()
        }
    }
}
