package ceui.pixiv.ui.bulk

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.core.GlideConfiguration
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import com.blankj.utilcode.util.Utils
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en",
    shadows = [BulkSelectRecreationTest.NoNetworkGlideModule::class])
class BulkSelectRecreationTest {
    private lateinit var controller: ActivityController<HostActivity>

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        Glide.tearDown()
    }

    @Test
    fun `illustration selection survives repeated activity recreation`() {
        val key = IllustBulkSelectHandoff.put(listOf(Illust(id = 1L), Illust(id = 2L)))
        show(BulkSelectV3Fragment.newInstance(key))
        row(0).itemView.performClick()

        repeat(2) {
            controller.recreate()
            awaitItems()
            assertEquals(2, list().adapter!!.itemCount)
            assertEquals(View.VISIBLE, row(0).itemView.findViewById<View>(R.id.checkBadge).visibility)
            assertEquals(View.GONE, row(1).itemView.findViewById<View>(R.id.checkBadge).visibility)
        }
    }

    @Test
    fun `novel selection survives repeated activity recreation`() {
        val key = NovelBulkSelectHandoff.put(listOf(Novel(id = 1L), Novel(id = 2L)))
        show(NovelBulkSelectV3Fragment.newInstance(key))
        row(0).itemView.performClick()

        repeat(2) {
            controller.recreate()
            awaitItems()
            assertEquals(2, list().adapter!!.itemCount)
            assertTrue(row(0).itemView.isSelected)
            assertFalse(row(1).itemView.isSelected)
        }
    }

    @Test
    fun `novel locale change rebuilds metadata and preserves selection`() {
        val key = NovelBulkSelectHandoff.put(listOf(
            Novel(id = 1L, text_length = 12345, is_bookmarked = true),
            Novel(id = 2L, text_length = 6789),
        ))
        show(NovelBulkSelectV3Fragment.newInstance(key))
        row(0).itemView.performClick()
        val oldMeta = row(0).itemView.findViewById<TextView>(R.id.meta).text.toString()

        val configuration = Configuration(controller.get().resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
        controller.configurationChange(configuration)
        awaitItems()

        val activity = controller.get()
        val words = activity.getString(
            R.string.v3_novel_word_count, NumberFormat.getIntegerInstance(Locale.JAPANESE).format(12345),
        )
        val expected = activity.getString(R.string.bulk_select_novel_meta_bookmarked, words)
        val restored = row(0).itemView
        assertTrue("The locale change must use different resources", expected != oldMeta)
        assertEquals(expected, restored.findViewById<TextView>(R.id.meta).text.toString())
        assertTrue(restored.isSelected)
        assertFalse(row(1).itemView.isSelected)
        assertEquals(2, list().adapter!!.itemCount)
    }

    private fun show(fragment: Fragment) {
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "bulk").commitNow()
        awaitItems()
    }

    private fun list(): RecyclerView {
        val view = controller.get().supportFragmentManager.findFragmentByTag("bulk")!!.requireView()
        return view.findViewById<RecyclerView>(R.id.list) ?: view.findViewById(R.id.grid)
    }

    private fun awaitItems() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (list().adapter!!.itemCount == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Bulk preparation did not complete", list().adapter!!.itemCount > 0)
    }

    private fun row(position: Int): RecyclerView.ViewHolder {
        val list = list()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(list.findViewHolderForAdapterPosition(position))
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppTheme)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }

    // Exercise real row binding without initializing Shaft's application-wide network client.
    @Implements(GlideConfiguration::class, isInAndroidSdk = false)
    class NoNetworkGlideModule {
        @Implementation
        fun registerComponents(context: Context, glide: Glide, registry: Registry) = Unit
    }
}
