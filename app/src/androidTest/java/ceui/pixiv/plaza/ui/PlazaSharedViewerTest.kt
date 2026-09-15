package ceui.pixiv.plaza.ui

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.fragments.FragmentImageDetail
import ceui.lisa.view.DragDismissLayout
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.TemplateRoute
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses read-only existing media; no posts or media are created. */
@RunWith(AndroidJUnit4::class)
class PlazaSharedViewerTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun awaitCondition(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = check() }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue(message, ready)
    }

    private fun grid(view: View): PlazaIllustGrid? =
        when (view) {
            is PlazaIllustGrid -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { grid(view.getChildAt(it)) }
            else -> null
        }

    @Test
    fun thumbnailOpensSharedViewerAndDragDismissesWithTransition() {
        val post = runBlocking { PlazaRepository.api.feed().items.first { it.images.isNotEmpty() } }
        val intent =
            Intent(instrumentation.targetContext, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_POST_DETAIL.key)
                .putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, post.id)
        var monitor = instrumentation.addMonitor(ImageDetailActivity::class.java.name, null, false)
        var viewer: ImageDetailActivity? = null
        try {
            ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
                var photo: ImageView? = null
                awaitCondition("Post thumbnail must be laid out") {
                    scenario.onActivity { activity ->
                        photo =
                            (grid(activity.window.decorView)?.getChildAt(0) as? ViewGroup)
                                ?.getChildAt(0) as? ImageView
                    }
                    photo?.width?.let { it > 0 } == true
                }
                val expectedBounds = IntArray(4)
                instrumentation.runOnMainSync {
                    val target = photo!!
                    val loc = IntArray(2)
                    target.getLocationOnScreen(loc)
                    expectedBounds[0] = loc[0]
                    expectedBounds[1] = loc[1]
                    expectedBounds[2] = loc[0] + target.width
                    expectedBounds[3] = loc[1] + target.height
                    target.performClick()
                    target.performClick()
                }
                viewer =
                    instrumentation.waitForMonitorWithTimeout(monitor, 20000)
                        as? ImageDetailActivity
                val activity = checkNotNull(viewer)
                assertArrayEquals(
                    expectedBounds,
                    activity.intent.getIntArrayExtra(ImageDetailActivity.EXTRA_ENTER_BOUNDS),
                )
                awaitCondition("Shared ZoomImage must display the post image") {
                    activity
                        .findViewById<SketchZoomImageView>(R.id.image)
                        ?.zoomable
                        ?.contentSizeState
                        ?.value
                        ?.width
                        ?.let { it > 0 } == true
                }
                assertEquals("Rapid taps must open only one viewer", 1, monitor.hits)
                var pager: ViewPager? = null
                instrumentation.runOnMainSync {
                    pager = activity.findViewById(R.id.view_pager)
                    assertTrue(pager!!.parent is DragDismissLayout)
                    assertTrue(
                        activity.supportFragmentManager.fragments.any { it is FragmentImageDetail }
                    )
                }
                awaitCondition("Entry animation must finish before dragging") {
                    !(pager!!.parent as DragDismissLayout).dragSuspended
                }
                // Pull down from the top of the freshly opened image, just like illustration
                // detail.
                val coords = IntArray(2)
                var height = 0
                var width = 0
                instrumentation.runOnMainSync {
                    pager!!.getLocationOnScreen(coords)
                    height = pager!!.height
                    width = pager!!.width
                }
                val down = SystemClock.uptimeMillis()
                fun pointer(action: Int, fraction: Float) {
                    val event =
                        MotionEvent.obtain(
                            down,
                            SystemClock.uptimeMillis(),
                            action,
                            coords[0] + width * .5f,
                            coords[1] + height * fraction,
                            0,
                        )
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                pointer(MotionEvent.ACTION_DOWN, .25f)
                for (step in 1..15) {
                    SystemClock.sleep(16)
                    pointer(MotionEvent.ACTION_MOVE, .25f + .5f * step / 15)
                }
                instrumentation.runOnMainSync {
                    assertTrue(
                        "Dismiss drag must scale the same viewer content",
                        pager!!.scaleX < 1f,
                    )
                    assertTrue(
                        "Dismiss drag must fade the shared black backdrop",
                        (pager!!.parent as View).background.alpha < 255,
                    )
                }
                pointer(MotionEvent.ACTION_UP, .75f)
                awaitCondition("Drag release must finish the image activity") {
                    activity.isDestroyed
                }
                instrumentation.waitForIdleSync()
                monitor =
                    instrumentation.addMonitor(ImageDetailActivity::class.java.name, null, false)
                scenario.onActivity { photo!!.performClick() }
                viewer =
                    instrumentation.waitForMonitorWithTimeout(monitor, 20000)
                        as? ImageDetailActivity
                val reopened = checkNotNull(viewer)
                assertNotSame(activity, reopened)
                assertEquals("Closing the viewer must allow opening it again", 1, monitor.hits)
                instrumentation.runOnMainSync { reopened.onBackPressedDispatcher.onBackPressed() }
                awaitCondition("Reopened viewer must also close normally") { reopened.isDestroyed }
            }
        } finally {
            instrumentation.runOnMainSync { viewer?.takeUnless { it.isDestroyed }?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun illustrationEntryStillLoadsAndDoubleTapZooms() {
        val media = runBlocking {
            PlazaRepository.api.feed().items.first { it.images.isNotEmpty() }.images.first()
        }
        // Local illustration model borrowing read-only media; no Pixiv mutation is performed.
        val illust =
            ceui.pixiv.api.model.Illust(
                id = -987654321,
                type = "illust",
                page_count = 1,
                width = media.width,
                height = media.height,
                meta_single_page = ceui.pixiv.api.model.MetaSinglePage(media.url),
            )
        val intent =
            Intent(instrumentation.targetContext, ImageDetailActivity::class.java)
                .putExtra("dataType", "二级详情")
                .putExtra("illust", illust)
        ActivityScenario.launch<ImageDetailActivity>(intent).use { scenario ->
            var activity: ImageDetailActivity? = null
            scenario.onActivity { activity = it }
            awaitCondition("Existing illustration entry must still render its image") {
                activity!!
                    .findViewById<SketchZoomImageView>(R.id.image)
                    ?.zoomable
                    ?.contentSizeState
                    ?.value
                    ?.width
                    ?.let { it > 0 } == true
            }
            val location = IntArray(2)
            var initialScale = 0f
            var image: SketchZoomImageView? = null
            instrumentation.runOnMainSync {
                assertNull(activity!!.plazaImageSource)
                assertEquals(illust.id, activity!!.mIllust!!.id)
                image = activity!!.findViewById(R.id.image)
                image!!.getLocationOnScreen(location)
                location[0] += image!!.width / 2
                location[1] += image!!.height / 2
                initialScale = image!!.zoomable.transformState.value.scaleX
            }
            repeat(2) {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event =
                        MotionEvent.obtain(
                            down,
                            SystemClock.uptimeMillis(),
                            action,
                            location[0].toFloat(),
                            location[1].toFloat(),
                            0,
                        )
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                    SystemClock.sleep(30)
                }
            }
            awaitCondition("Existing double-tap gesture must change image scale") {
                kotlin.math.abs(image!!.zoomable.transformState.value.scaleX - initialScale) > .01f
            }
        }
    }

    @Test
    fun ninePagesKeepSelectedIndexAcrossRecreationAndUseAnimatedBack() {
        val post = runBlocking { PlazaRepository.api.feed().items.first { it.images.isNotEmpty() } }
        // Repeat authorized media locally to exercise all nine positions without publishing
        // fixtures.
        val images = List(9) { post.images[it % post.images.size] }
        val intent =
            Intent(instrumentation.targetContext, ImageDetailActivity::class.java)
                .putExtra("dataType", PlazaImageViewer.DATA_TYPE)
                .putExtra("index", 4)
                .putExtra(PlazaImageViewer.EXTRA_IMAGES, Gson().toJson(images))
                .putExtra(PlazaImageViewer.EXTRA_POST, post.id)
                .putExtra(PlazaImageViewer.EXTRA_VIEWER, SessionManager.loggedInUid)
                .putExtra(ImageDetailActivity.EXTRA_ENTER_BOUNDS, intArrayOf(30, 200, 330, 500))
        ActivityScenario.launch<ImageDetailActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager>(R.id.view_pager)
                assertEquals(9, pager.adapter!!.count)
                assertEquals(4, pager.currentItem)
                assertTrue(
                    activity.supportFragmentManager.fragments.count {
                        it.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    } <= 1
                )
                pager.setCurrentItem(8, false)
                assertEquals(
                    activity.getString(R.string.plaza_image_position, 9, 9),
                    activity.findViewById<TextView>(R.id.current_page).text.toString(),
                )
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager>(R.id.view_pager)
                assertEquals(8, pager.currentItem)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(
                    "Back must run the shared exit animation",
                    (pager.parent as DragDismissLayout).dragSuspended,
                )
            }
            SystemClock.sleep(500)
        }
    }
}
