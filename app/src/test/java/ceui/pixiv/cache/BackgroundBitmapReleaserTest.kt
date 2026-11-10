package ceui.pixiv.cache

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.request.Request
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundBitmapReleaserTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val releaser = BackgroundBitmapReleaser()

    @Before
    fun setUp() {
        // 不走 install：那一步还会给全局 SingletonSketch 挂工厂，这里只测页面上的 Glide 请求。
        app.registerActivityLifecycleCallbacks(releaser)
    }

    @After
    fun tearDown() {
        app.unregisterActivityLifecycleCallbacks(releaser)
    }

    @Test
    fun `UI_HIDDEN releases only covered pages, BACKGROUND releases the top too`() {
        val bottom = open()
        val covered = FakeRequest(complete = true)
        val loading = FakeRequest(complete = false, running = true)
        bottom.get().addImage(covered)
        bottom.get().addImage(loading)
        val top = coverWith(bottom)
        val shown = FakeRequest(complete = true)
        top.get().addImage(shown)
        top.pause().stop()

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(1, covered.clears)
        assertEquals("进行中的请求交给 Glide 自己的 onStop", 0, loading.clears)
        assertEquals("切出去马上回来，眼前这页不能重载", 0, shown.clears)

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(1, shown.clears)
        assertEquals("已释放的不重复 clear", 1, covered.clears)
    }

    @Test
    fun `page under a translucent top still counts as visible on UI_HIDDEN`() {
        val under = open()
        val request = FakeRequest(complete = true)
        under.get().addImage(request)
        under.pause() // 半透明页盖上来：下面这层只 pause 不 stop，仍对用户可见
        val viewer = open()
        viewer.pause()
        viewer.stop()
        under.stop()

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertEquals(0, request.clears)
    }

    @Test
    fun `restarted page gets back the requests it lost`() {
        val page = open()
        val request = FakeRequest(complete = true)
        page.get().addImage(request)
        page.pause().stop()

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(request.isCleared)

        page.start()
        assertEquals(1, request.begins)
        assertFalse(request.isCleared)
    }

    @Test
    fun `request replaced while in background is left to its owner`() {
        val page = open()
        val old = FakeRequest(complete = true)
        val image = page.get().addImage(old)
        page.pause().stop()
        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        val replacement = FakeRequest(complete = false, running = true)
        image.setTag(GLIDE_TAG, replacement)
        page.start()

        assertEquals(0, old.begins)
        assertEquals(0, replacement.begins)
    }

    @Test
    fun `visible pages are never released`() {
        val page = open()
        val request = FakeRequest(complete = true)
        page.get().addImage(request)

        releaser.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertEquals(0, request.clears)
    }

    private fun open(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    /** 按真实顺序盖住 [below]：below.onPause → 新页 resume → below.onStop。 */
    private fun coverWith(below: ActivityController<Activity>): ActivityController<Activity> {
        below.pause()
        val top = open()
        below.stop()
        return top
    }

    private fun Activity.addImage(request: Request): ImageView =
        ImageView(this).also {
            it.setTag(GLIDE_TAG, request)
            findViewById<FrameLayout>(android.R.id.content).addView(it)
        }

    private class FakeRequest(private var complete: Boolean, private var running: Boolean = false) : Request {
        private var cleared = false
        var clears = 0
        var begins = 0

        override fun begin() {
            begins++
            cleared = false
            complete = true
        }

        override fun clear() {
            clears++
            cleared = true
            complete = false
            running = false
        }

        override fun pause() = clear()
        override fun isRunning() = running
        override fun isComplete() = complete
        override fun isCleared() = cleared
        override fun isAnyResourceSet() = complete
        override fun isEquivalentTo(other: Request?) = other === this
    }

    private companion object {
        val GLIDE_TAG = com.bumptech.glide.R.id.glide_custom_view_target_tag
    }
}
