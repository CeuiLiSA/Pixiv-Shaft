package ceui.pixiv.ui.trending

import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.TrendingTag
import ceui.pixiv.feeds.FeedAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.DrawableImageViewTarget
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = HotTagsImageRecycleTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HotTagsImageRecycleTest {
    class TestApplication : Shaft() {
        private val client = OkHttpClient()
        override fun onCreate() = Unit
        override fun getOkHttpClient() = client
    }

    private lateinit var activity: ActivityController<FragmentActivity>
    private lateinit var fragment: HotTagsFeedFragment
    private lateinit var list: RecyclerView

    @Before
    fun setUp() {
        // Exercise the real renderer without the app's native services or network loaders.
        Glide.init(RuntimeEnvironment.getApplication(), GlideBuilder())
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup()
        fragment = HotTagsFeedFragment.newInstance(Params.TYPE_ILLUST)
        activity.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            // The tab has a view, but its lazy network load must not run.
            .setMaxLifecycle(fragment, Lifecycle.State.STARTED)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        list = fragment.requireView().findViewById(ceui.pixiv.feeds.R.id.feed_list_view)
        val items = (0 until 30).map { index ->
            val tag = TrendingTag(tag = "tag-$index")
            if (index == 0) HotTagHeaderItem(tag, null) else HotTagGridItem(tag, null)
        }
        (list.adapter as FeedAdapter).apply {
            submitList(null)
            submitList(items)
        }
        list.visibility = View.VISIBLE
        layout()
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
        Glide.tearDown()
    }

    @Test
    fun `scrolling keeps cached requests but destroying the view clears every request`() {
        val header = requireNotNull(list.findViewHolderForAdapterPosition(0))
        val cachedImage = header.itemView.findViewById<ImageView>(R.id.illust_image)
        val cachedRequest = request(cachedImage)

        list.scrollToPosition(15)
        layout()
        assertNull("The header is now outside the attached children", list.findViewHolderForAdapterPosition(0))
        assertSame(cachedRequest, request(cachedImage))
        assertFalse("Scrolling within the cache must not clear the image", cachedRequest.isCleared)
        val visibleImage = list.getChildAt(0).findViewById<ImageView>(R.id.illust_image)
        val visibleRequest = request(visibleImage)

        activity.get().supportFragmentManager.beginTransaction().detach(fragment).commitNow()

        assertTrue("The fragment is retained while its view is destroyed", fragment.isDetached)
        assertNull(fragment.view)
        assertTrue("Offscreen cached requests must be released", cachedRequest.isCleared)
        assertNull(DrawableImageViewTarget(cachedImage).request)
        assertTrue("Attached requests must also be released", visibleRequest.isCleared)
        assertNull(DrawableImageViewTarget(visibleImage).request)
    }

    @Test
    fun `evicting a holder into the recycled pool clears its image request`() {
        val header = requireNotNull(list.findViewHolderForAdapterPosition(0))
        val image = header.itemView.findViewById<ImageView>(R.id.illust_image)
        val request = request(image)
        list.setItemViewCacheSize(0)

        list.scrollToPosition(15)
        layout()

        assertTrue("The header holder has entered the pool", list.recycledViewPool.getRecycledViewCount(0) > 0)
        assertTrue("A recycled holder must release its request", request.isCleared)
        assertNull(DrawableImageViewTarget(image).request)
    }

    private fun request(image: ImageView): Request {
        val request = DrawableImageViewTarget(image).request
        assertNotNull("The renderer must have bound a Glide request", request)
        return requireNotNull(request)
    }

    private fun layout() {
        val width = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
        list.measure(width, height)
        list.layout(0, 0, 900, 900)
    }
}
