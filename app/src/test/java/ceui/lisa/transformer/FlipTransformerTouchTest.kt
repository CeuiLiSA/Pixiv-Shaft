package ceui.lisa.transformer

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class FlipTransformerTouchTest {
    @Test
    fun `horizontal flip leaves touches with the visible page`() {
        assertVisiblePageReceivesTouches(FlipHorizontalTransformer())
    }

    @Test
    fun `vertical flip leaves touches with the visible page`() {
        assertVisiblePageReceivesTouches(FlipVerticalTransformer())
    }

    private fun assertVisiblePageReceivesTouches(transformer: ABaseTransformer) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val host = FrameLayout(activity)
            val touches = mutableListOf<Int>()
            fun page(id: Int) = FrameLayout(activity).apply {
                addView(View(activity).apply {
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) touches += id
                        true
                    }
                }, FrameLayout.LayoutParams(600, 800))
                host.addView(this, FrameLayout.LayoutParams(600, 800))
            }
            val current = page(0)
            val next = page(1)
            activity.setContentView(host)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 600, 800)
            // A pager lays out the next page one width to the right. The flip transformer
            // cancels that offset, so its transparent back face overlaps the current page.
            next.layout(600, 0, 1200, 800)
            transformer.transformPage(current, 0f)
            transformer.transformPage(next, 1f)
            tap(host)
            assertEquals(listOf(0), touches)

            transformer.transformPage(current, -1f)
            transformer.transformPage(next, 0f)
            current.layout(-600, 0, 0, 800)
            next.layout(0, 0, 600, 800)
            tap(host)
            assertEquals("The newly selected page becomes interactive again", listOf(0, 1), touches)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun tap(host: View) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, 300f, 400f, 0)
            try {
                host.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
