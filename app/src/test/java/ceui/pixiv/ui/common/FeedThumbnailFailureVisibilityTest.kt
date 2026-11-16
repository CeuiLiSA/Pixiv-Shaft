package ceui.pixiv.ui.common

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.dynamic.TimelineGridAdapter
import ceui.pixiv.utils.INetworkState
import ceui.pixiv.utils.NetworkStateManager.NetworkType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FeedThumbnailFailureVisibilityTest {
    private lateinit var activity: ActivityController<Activity>
    private val network = MutableLiveData(NetworkType.WIFI)

    @Before
    fun setUp() {
        FeedThumbnailFailureNotifier.resetForTesting()
        FeedThumbnailFailureNotifier.init(object : INetworkState {
            override val networkState = network
        })
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity.get().setTheme(R.style.AppTheme)
    }

    @After
    fun tearDown() {
        FeedThumbnailFailureNotifier.resetForTesting()
        activity.pause().stop().destroy()
    }

    @Test
    fun `hidden and horizontally clipped cards cannot consume the process reminder`() {
        val card = card(true)
        val list = listOfViews(card)
        show(list)
        assertEquals(1 to 1, counts(list))
        card.translationX = 500f
        assertEquals(0 to 0, counts(list))
        FeedThumbnailFailureNotifier.checkAndNotify(list)
        assertFalse(FeedThumbnailFailureNotifier.isPendingTrigger())
        card.translationX = 0f
        list.visibility = View.INVISIBLE
        assertEquals(0 to 0, counts(list))
        list.visibility = View.VISIBLE
        list.translationX = 500f // a retained ViewPager page outside the viewport
        assertEquals(0 to 0, counts(list))
    }

    @Test
    fun `nested rank rail shares the screen denominator without counting its header`() {
        val rail = listOfViews(card(true), card(true), horizontal = true)
        val header = FrameLayout(activity.get()).apply {
            layoutParams = RecyclerView.LayoutParams(400, 200)
            addView(rail, FrameLayout.LayoutParams(400, 200))
        }
        val list = listOfViews(header, card(false), card(false))
        show(list)
        assertEquals(4 to 2, counts(list))
        assertEquals(counts(list), counts(rail))
        FeedThumbnailFailureNotifier.checkAndNotify(rail)
        assertFalse(FeedThumbnailFailureNotifier.isPendingTrigger())
    }

    @Test
    fun `a timeline grid counts as one artwork regardless of its page count`() {
        val grid = listOfViews(card(true), card(true), horizontal = true)
        val root = card(true).apply { addView(grid, FrameLayout.LayoutParams(400, 200)) }
        val list = listOfViews(root, card(false))
        show(list)
        assertEquals(2 to 1, counts(list))
    }

    @Test
    fun `pending startup reminder is revalidated after recovery and after navigation`() {
        val card = card(true)
        val list = listOfViews(card)
        show(list)
        FeedThumbnailFailureNotifier.checkAndNotify(list)
        assertTrue(FeedThumbnailFailureNotifier.isPendingTrigger())
        card.setTag(R.id.tag_feed_thumbnail_load_failed, false)
        FeedThumbnailFailureNotifier.onBannerReady()
        assertFalse(FeedThumbnailFailureNotifier.isPendingTrigger())
        assertFalse(FeedThumbnailFailureNotifier.isPopped())

        card.setTag(R.id.tag_feed_thumbnail_load_failed, true)
        FeedThumbnailFailureNotifier.checkAndNotify(list)
        assertTrue(FeedThumbnailFailureNotifier.isPendingTrigger())
        activity.get().setContentView(FrameLayout(activity.get()))
        FeedThumbnailFailureNotifier.onBannerReady()
        assertFalse(FeedThumbnailFailureNotifier.isPendingTrigger())
    }

    @Test
    fun `failure before holder attachment is checked after its first layout`() {
        val card = card(false)
        FeedThumbnailFailureNotifier.onThumbnailFailed(card)
        val list = listOfViews(card)
        show(list)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(FeedThumbnailFailureNotifier.isPendingTrigger())
    }

    @Test
    fun `real network observer skips sticky and duplicate emissions but resets on change`() {
        FeedThumbnailFailureNotifier.setPoppedForTesting(true)
        network.value = NetworkType.WIFI
        assertTrue(FeedThumbnailFailureNotifier.isPopped())
        network.value = NetworkType.CELLULAR
        assertFalse(FeedThumbnailFailureNotifier.isPopped())
        val list = listOfViews(card(true))
        show(list)
        FeedThumbnailFailureNotifier.checkAndNotify(list)
        assertTrue(FeedThumbnailFailureNotifier.isPendingTrigger())
        network.value = NetworkType.NONE
        assertFalse(FeedThumbnailFailureNotifier.isPendingTrigger())
    }

    @Test
    fun `grid synchronous Glide failure reaches the outer card and survives an unchanged rebind`() {
        val root = card(false)
        val grid = RecyclerView(activity.get()).apply {
            layoutManager = GridLayoutManager(context, 2)
        }
        // Match the real hierarchy: artwork -> CardView -> FrameLayout -> RecyclerView.
        root.addView(FrameLayout(activity.get()).apply {
            addView(FrameLayout(activity.get()).apply {
                addView(grid, FrameLayout.LayoutParams(400, 200))
            }, FrameLayout.LayoutParams(400, 200))
        }, FrameLayout.LayoutParams(400, 200))
        val adapter = TimelineGridAdapter(root) {}
        grid.adapter = adapter
        val bean = Illust(id = 1, page_count = 2) // null URL fails synchronously inside into()
        adapter.submit(bean, 2, 200, false, 0, false)
        val list = listOfViews(root)
        show(list)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1 to 1, counts(list))
        assertTrue(FeedThumbnailFailureNotifier.isPendingTrigger())
        adapter.submit(bean.copy(total_view = 1), 2, 200, false, 0, false)
        show(list)
        assertEquals(1 to 1, counts(list))

        adapter.submit(bean.copy(id = 2), 2, 200, false, 0, false)
        assertEquals(1 to 0, counts(list)) // new artwork starts in loading state
        show(list)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1 to 1, counts(list)) // same null URL still belongs to a new request
    }

    private fun counts(list: RecyclerView) = FeedThumbnailFailureNotifier.visibleFailureCounts(list)

    private fun card(failed: Boolean) = FrameLayout(activity.get()).apply {
        layoutParams = RecyclerView.LayoutParams(200, 200)
        setTag(R.id.tag_feed_thumbnail_load_failed, failed)
        addView(ImageView(context).apply { id = R.id.illust_image }, FrameLayout.LayoutParams(200, 200))
    }

    private fun listOfViews(vararg views: View, horizontal: Boolean = false) =
        RecyclerView(activity.get()).apply {
            layoutManager = LinearLayoutManager(
                context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false,
            )
            itemAnimator = null
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = views.size
                override fun getItemViewType(position: Int) = position
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(views[viewType]) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }

    private fun show(list: RecyclerView) {
        if (!list.isAttachedToWindow) activity.get().setContentView(list)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 400, 600)
    }
}
