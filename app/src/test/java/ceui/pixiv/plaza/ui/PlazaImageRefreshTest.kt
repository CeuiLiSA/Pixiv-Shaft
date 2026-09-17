package ceui.pixiv.plaza.ui

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.pixiv.plaza.*
import ceui.pixiv.witstudio.theme.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.DrawableImageViewTarget
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlazaImageRefreshTest {
    private val image =
        PlazaImage(
            "media-1",
            960,
            1200,
            "image/jpeg",
            "https://media.pixshaft.com/photo.jpg?signature=old",
            1,
        )

    private fun post(images: List<PlazaImage>) =
        PlazaPost(1, 42, "Author", "Text", 1, null, null, null, 0, 0, false, images)

    private fun layout(view: PostView, width: Int = 390) {
        val px = view.context.dp(width)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, px, view.measuredHeight)
        grid(view).onMeasured?.invoke(grid(view).width)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, px, view.measuredHeight)
    }

    private fun grid(view: PostView) =
        (0 until view.childCount).map(view::getChildAt).filterIsInstance<PlazaIllustGrid>().single()

    private fun photo(view: PostView) =
        ((grid(view).getChildAt(0) as ViewGroup).getChildAt(0) as ImageView)

    private class CompletedRequest(private val complete: Boolean = true) : Request {
        var cleared = false

        override fun begin() = Unit

        override fun clear() {
            cleared = true
        }

        override fun pause() = Unit

        override fun isRunning() = false

        override fun isComplete() = complete && !cleared

        override fun isCleared() = cleared

        override fun isAnyResourceSet() = !cleared

        override fun isEquivalentTo(other: Request?) = other === this
    }

    @Test
    fun `rotated signature retains loaded image and updates viewer click data`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = PostView(context) { 42L }
        view.bind(post(listOf(image)), false, true, {}, {}, { _, _, _ -> })
        layout(view)
        val original = photo(view)
        Glide.with(original).clear(original)
        val request = CompletedRequest()
        DrawableImageViewTarget(original).request = request
        val drawable = ColorDrawable(0xff334455.toInt())
        original.setImageDrawable(drawable)
        val refreshed =
            post(listOf(image.copy(url = image.url.replace("old", "new"), expiresAt = 99999)))
        var opened: PlazaPost? = null
        var clickedView: android.view.View? = null
        view.bind(
            refreshed,
            false,
            true,
            {},
            {},
            { p, _, thumbnail ->
                opened = p
                clickedView = thumbnail
            },
        )
        layout(view)
        assertSame(original, photo(view))
        assertSame(drawable, photo(view).drawable)
        assertSame(request, DrawableImageViewTarget(original).request)
        assertFalse(request.cleared)
        original.performClick()
        assertEquals(refreshed, opened)
        assertSame(original, clickedView)
        view.bind(
            post(listOf(image.copy(mediaId = "another-image"))),
            false,
            true,
            {},
            {},
            { _, _, _ -> },
        )
        layout(view)
        assertNotSame(original, photo(view))
        assertTrue(request.cleared)
        view.clear()
    }

    @Test
    fun `failed request retries with refreshed signature without replacing the image view`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = PostView(context) { 42L }
        view.bind(post(listOf(image)), false, true, {}, {}, { _, _, _ -> })
        layout(view)
        val original = photo(view)
        Glide.with(original).clear(original)
        val failed = CompletedRequest(false)
        DrawableImageViewTarget(original).request = failed
        val refreshed = image.copy(url = image.url.replace("old", "fresh"), expiresAt = 999999)
        view.bind(post(listOf(refreshed)), false, true, {}, {}, { _, _, _ -> })
        assertSame(original, photo(view))
        assertTrue(failed.cleared)
        assertNotSame(failed, DrawableImageViewTarget(original).request)
        view.clear()
    }

    @Test
    fun `signed URLs share disk bytes but never share stale transport models`() {
        val old = PlazaMediaUrl(image, 42)
        val fresh = PlazaMediaUrl(image.copy(url = image.url.replace("old", "new")), 42)
        fun digest(url: PlazaMediaUrl) =
            MessageDigest.getInstance("SHA-256").apply { url.updateDiskCacheKey(this) }.digest()
        assertArrayEquals(digest(old), digest(fresh))
        assertNotEquals(old, fresh)
        assertTrue(fresh.toStringUrl().endsWith("signature=new"))
        assertFalse(digest(old).contentEquals(digest(PlazaMediaUrl(image, 43))))
        assertFalse(
            digest(old).contentEquals(digest(PlazaMediaUrl(image.copy(mediaId = "another"), 42)))
        )
    }

    @Test
    fun `recycling after activity destruction only clears existing requests`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val view = PostView(activity) { 42L }
        activity.setContentView(view)
        view.bind(post(listOf(image)), false, true, {}, {}, { _, _, _ -> })
        layout(view)
        controller.pause().stop().destroy()
        assertTrue(activity.isDestroyed)
        PostAdapter({}, {}, { _, _, _ -> }).onViewRecycled(PostAdapter.Holder(view))
        assertEquals(0, grid(view).childCount)
    }
}
