package ceui.pixiv.ui.detail

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedAdapter
import com.blankj.utilcode.util.Utils
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = ArtworkPageReuseTest.TestApplication::class)
class ArtworkPageReuseTest {
    class TestApplication : Shaft() {
        private val client by lazy { OkHttpClient() }
        override fun onCreate() = Unit
        override fun getOkHttpClient() = client
    }

    private lateinit var host: FragmentActivity
    private lateinit var owner: Fragment
    private var db: AppDatabase? = null
    private val delegates = mutableListOf<IllustAdapter>()
    private val work = Illust(id = 1144L, type = "illust", page_count = 12, width = 600, height = 800)

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Glide.init(app, GlideBuilder())
        host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme)
        owner = Fragment()
        host.supportFragmentManager.beginTransaction().add(owner, "owner").commitNow()
    }

    @After
    fun tearDown() {
        delegates.forEach { it.release() }
        db?.let { AppDatabase.destroyInstance(); it.close() }
        host.finish()
        Glide.tearDown()
    }

    @Test
    fun `pooled first page keeps receiving original progress and completion without rebinding`() {
        val adapter = delegate()
        val holder = boundPage(adapter)
        val progress = MutableLiveData<Int>()
        val observer = Observer<Int> { value ->
            holder.baseBind.progressLayout.donutProgress.progress = value
            if (value == 100) {
                holder.baseBind.illustHd.setImageDrawable(ColorDrawable(Color.BLUE))
                holder.baseBind.illustHd.visibility = View.VISIBLE
                holder.baseBind.progressLayout.donutProgress.visibility = View.GONE
            }
        }
        progress.observe(host, observer)
        holder.itemView.setTag(R.id.tag_task_observers, Runnable { progress.removeObserver(observer) })
        progress.value = 25

        adapter.onViewRecycled(holder)
        assertTrue("p0 must retain its sole original-load observer", progress.hasObservers())
        assertTrue(adapter.tryKeepPinnedPage(holder, 0))
        progress.value = 100
        assertEquals(View.VISIBLE, holder.baseBind.illustHd.visibility)
        assertEquals(View.GONE, holder.baseBind.progressLayout.donutProgress.visibility)

        // Repeat the pool round trip using a new thin ViewHolder shell, as the renderer does.
        val nextShell = ViewHolder(holder.baseBind)
        adapter.onViewRecycled(nextShell)
        assertTrue(adapter.tryKeepPinnedPage(nextShell, 0))
        adapter.onViewRecycled(nextShell)
        adapter.release()
        assertFalse(progress.hasObservers())
        assertNull(holder.baseBind.illust.drawable)
        assertNull(holder.baseBind.illustHd.drawable)
        assertFalse(adapter.tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `ordinary pages still detach observers and clear images on recycle`() {
        val adapter = delegate()
        val holder = boundPage(adapter, 1)
        var detached = false
        holder.itemView.setTag(R.id.tag_task_observers, Runnable { detached = true })
        adapter.onViewRecycled(holder)
        assertTrue(detached)
        assertNull(holder.baseBind.illust.drawable)
        assertNull(holder.baseBind.illust.getTag(R.id.tag_image_url))
        assertFalse(adapter.tryKeepPinnedPage(holder, 1))
    }

    @Test
    fun `legacy adapter does not retain the first page`() {
        val adapter = delegate(pinned = false)
        val holder = boundPage(adapter)
        var detached = false
        holder.itemView.setTag(R.id.tag_task_observers, Runnable { detached = true })
        adapter.onViewRecycled(holder)
        assertTrue(detached)
        assertNull(holder.baseBind.illust.drawable)
        assertFalse(adapter.tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `failed original with large placeholder remains eligible for retry`() {
        val adapter = delegate()
        val holder = boundPage(adapter)
        adapter.onViewRecycled(holder)
        holder.baseBind.reload.visibility = View.VISIBLE
        assertFalse(adapter.tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `new adapter cannot reuse the previous adapters first page`() {
        val old = delegate()
        val holder = boundPage(old)
        old.onViewRecycled(holder)
        assertFalse(delegate().tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `local file arrival invalidates a pooled network preview`() {
        val adapter = delegate()
        val holder = boundPage(adapter)
        adapter.onViewRecycled(holder)
        adapter.putLocalPageUri(0, Uri.parse("file:///downloaded-original.png"))
        assertFalse(adapter.tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `reuse is consumed once so later explicit rebind is not swallowed`() {
        val adapter = delegate()
        val holder = boundPage(adapter)
        adapter.onViewRecycled(holder)
        assertTrue(adapter.tryKeepPinnedPage(holder, 0))
        assertFalse(adapter.tryKeepPinnedPage(holder, 0))
    }

    @Test
    fun `image refresh requires full binding while collapse only refreshes overlay`() {
        val renderer = ArtworkV3Fragment().artworkPinnedFirstPageRenderer()
        val initial = ArtworkPinnedFirstPageItem(work.id, 0)
        val collapsed = initial.withOverlayTick(1)
        assertNotNull(renderer.changePayload(initial, collapsed))
        assertNull(renderer.changePayload(initial, initial.withRebindTick(1)))
        assertNull(renderer.changePayload(initial, collapsed.withRebindTick(1)))
        assertEquals(initial.javaClass, collapsed.javaClass)
        assertEquals(initial.feedKey, collapsed.feedKey)
        assertNotEquals(initial, collapsed)
        assertEquals(1, collapsed.withRebindTick(1).overlayTick)
        assertEquals(1, initial.withRebindTick(1).withOverlayTick(1).rebindTick)
    }

    @Test
    fun `feed dispatch preserves image invalidation when merged with collapse payload`() {
        val fragment = ArtworkV3Fragment()
        val delegate = collapsibleDelegate()
        ReflectionHelpers.setField(fragment, "pageAdapter", delegate)
        val renderer = fragment.artworkPinnedFirstPageRenderer()
        val item = ArtworkPinnedFirstPageItem(work.id, 0)
        val adapter = FeedAdapter(listOf(renderer))
        adapter.submitList(listOf(item))
        val cell = adapter.createViewHolder(FrameLayout(host), 0)
        adapter.bindViewHolder(cell, 0)
        val binding = cell.binding as RecyIllustDetailBinding
        val overlayPayload = requireNotNull(renderer.changePayload(item, item.withOverlayTick(1)))
        for (payloads in listOf(mutableListOf(Any(), overlayPayload), mutableListOf(overlayPayload, Any()))) {
            binding.illust.setTag(R.id.tag_image_url, "stale-preview")
            adapter.onBindViewHolder(cell, 0, payloads)
            // Full binding reads the work's missing URL; overlay binding would leave the old tag.
            assertNull(binding.illust.getTag(R.id.tag_image_url))
        }
    }

    @Test
    fun `reopening pages cancels an uncommitted collapse scroll`() {
        val fragment = ArtworkV3Fragment().apply { arguments = Bundle() }
        val chrome = FragmentArtworkV3Binding.inflate(LayoutInflater.from(host))
        ReflectionHelpers.setField(fragment, "_chromeBind", chrome)
        ReflectionHelpers.setField(fragment, "pendingCollapseResetScroll", true)
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "onPagesExpandedChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertFalse(ReflectionHelpers.getField(fragment, "pendingCollapseResetScroll"))
        chrome.collapsePill.animate().cancel()
    }

    @Test
    fun `destroying the view discards its pending collapse scroll`() {
        val fragment = ArtworkV3Fragment()
        ReflectionHelpers.setField(fragment, "pendingCollapseResetScroll", true)
        fragment.onDestroyView()
        assertFalse(ReflectionHelpers.getField(fragment, "pendingCollapseResetScroll"))
    }

    private fun collapsibleDelegate(): CollapsibleIllustAdapter {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        return CollapsibleIllustAdapter(host, owner, work, 600, false).also { delegates += it }
    }

    private fun delegate(pinned: Boolean = true): IllustAdapter =
        object : IllustAdapter(host, owner, work, 600, false) {
            // No DB/network fixture: the work deliberately has no URLs. Exercise real bind/recycle.
            override fun scanLocalDownloads() = Unit
        }.also { it.setKeepPinnedPages(pinned); delegates += it }

    private fun boundPage(adapter: IllustAdapter, page: Int = 0): ViewHolder<RecyIllustDetailBinding> {
        val holder = ViewHolder(RecyIllustDetailBinding.inflate(LayoutInflater.from(host)))
        adapter.onBindViewHolder(holder, page)
        holder.baseBind.illust.setImageDrawable(ColorDrawable(Color.RED))
        holder.baseBind.illust.setTag(R.id.tag_image_url, "preview-$page")
        holder.baseBind.reload.visibility = View.GONE
        return holder
    }
}
