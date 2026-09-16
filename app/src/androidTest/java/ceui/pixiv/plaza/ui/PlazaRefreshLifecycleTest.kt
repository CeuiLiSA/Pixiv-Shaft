package ceui.pixiv.plaza.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import com.bumptech.glide.request.target.DrawableImageViewTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads an existing image post. Repeated refresh/finish never publishes or edits content. */
@RunWith(AndroidJUnit4::class)
class PlazaRefreshLifecycleTest {
    @Test
    fun refreshKeepsDisplayedPhotoAndRepeatedFinishDoesNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val post = runBlocking { PlazaRepository.api.feed().items.first { it.images.isNotEmpty() } }
        fun fragment(f: Fragment): PlazaPostDetailFragment? =
            if (f is PlazaPostDetailFragment) f
            else f.childFragmentManager.fragments.firstNotNullOfOrNull(::fragment)
        fun recycler(v: View): androidx.recyclerview.widget.RecyclerView? =
            if (v is androidx.recyclerview.widget.RecyclerView) v
            else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { recycler(v.getChildAt(it)) }
            else null
        fun grid(v: View): PlazaIllustGrid? =
            if (v is PlazaIllustGrid) v
            else if (v is ViewGroup)
                (0 until v.childCount).firstNotNullOfOrNull { grid(v.getChildAt(it)) }
            else null
        repeat(3) {
            val intent =
                Intent(instrumentation.targetContext, TemplateActivity::class.java)
                    .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_POST_DETAIL.key)
                    .putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, post.id)
            ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
                var original: ImageView? = null
                fun awaitReady(): ImageView {
                    val deadline = android.os.SystemClock.uptimeMillis() + 20000
                    var ready: ImageView? = null
                    while (ready == null && android.os.SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            val f =
                                activity.supportFragmentManager.fragments.firstNotNullOfOrNull(
                                    ::fragment
                                )
                            if (
                                f?.view != null &&
                                    !ViewModelProvider(f)[PlazaTimelineViewModel::class.java]
                                        .state
                                        .value
                                        .loading
                            ) {
                                val photo =
                                    (grid(f.requireView())?.getChildAt(0) as? ViewGroup)
                                        ?.getChildAt(0) as? ImageView
                                val bound =
                                    (recycler(f.requireView())?.adapter as? PostAdapter)
                                        ?.currentList
                                        ?.firstOrNull()
                                val expected =
                                    ViewModelProvider(f)[PlazaTimelineViewModel::class.java]
                                        .state
                                        .value
                                        .parent
                                if (
                                    photo != null &&
                                        bound == expected &&
                                        DrawableImageViewTarget(photo).request?.isComplete == true
                                )
                                    ready = photo
                            }
                        }
                        if (ready == null) android.os.SystemClock.sleep(50)
                    }
                    return checkNotNull(ready) { "Existing post image did not finish loading" }
                }
                original = awaitReady()
                val drawable = original.drawable
                android.os.SystemClock.sleep(1100) // Rotate the server signature timestamp.
                scenario.onActivity { activity ->
                    val f =
                        activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::fragment)!!
                    ViewModelProvider(f)[PlazaTimelineViewModel::class.java].refresh()
                }
                assertSame(original, awaitReady())
                assertSame(drawable, original.drawable)
                scenario.onActivity { it.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }
}
