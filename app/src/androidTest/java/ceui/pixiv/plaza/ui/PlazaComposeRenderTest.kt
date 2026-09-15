package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the real composer and fills a local draft; never taps Publish. */
@RunWith(AndroidJUnit4::class)
class PlazaComposeRenderTest {
    @Test
    fun renderComposerAndRestoreDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixtures =
            (0..2).map { index ->
                File(target.cacheDir, "plaza-review-$index.png").also { file ->
                    instrumentation.context.assets
                        .open("plaza-figma/imgGaleryImg${if(index==0) "" else index}.png")
                        .use { source -> file.outputStream().use(source::copyTo) }
                }
            }
        val intent =
            Intent(target, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_COMPOSE.key)
        try {
            ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
                fun compose(fragment: Fragment): PlazaComposeFragment? =
                    if (fragment is PlazaComposeFragment) fragment
                    else fragment.childFragmentManager.fragments.firstNotNullOfOrNull(::compose)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment =
                        activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::compose)!!
                    fragment
                        .requireView()
                        .findViewById<EditText>(R.id.plaza_draft_title)
                        .setText("New Zealand South Island Road Trip Itinerary")
                    fragment
                        .requireView()
                        .findViewById<EditText>(R.id.plaza_draft_text)
                        .setText(
                            "Route Overview\nTotal Distance: ~1,800 km\nRecommended Duration: 12–14 days\nBest Seasons: Spring & Autumn (Oct–Nov, Mar–Apr)\nTransport: Self-drive (Campervan or SUV)\nRoute Overview\nTotal Distance: ~1,800 km\nRecommended Duration: 12–14 days"
                        )
                    val model = ViewModelProvider(fragment)[PlazaComposeViewModel::class.java]
                    model.attach(fixtures.map(Uri::fromFile))
                    model.reference(123456, "illust")
                    activity.window.decorView.clearFocus()
                }
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val fragment =
                            activity.supportFragmentManager.fragments.firstNotNullOfOrNull(
                                ::compose
                            )!!
                        fun loaded(v: android.view.View): Int =
                            (if (
                                v is android.widget.ImageView &&
                                    v.contentDescription in
                                        (1..9).map {
                                            v.context.getString(R.string.plaza_selected_photo, it)
                                        } &&
                                    v.drawable != null
                            )
                                1
                            else 0) +
                                (if (v is android.view.ViewGroup)
                                    (0 until v.childCount).sumOf { loaded(v.getChildAt(it)) }
                                else 0)
                        ready = loaded(fragment.requireView()) == 3
                    }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("All selected photos must be loaded before capture", ready)
                scenario.onActivity { activity ->
                    val fragment =
                        activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::compose)!!
                    val model = ViewModelProvider(fragment)[PlazaComposeViewModel::class.java]
                    assertEquals(3, model.state.value.images.size)
                    assertEquals(123456L, model.state.value.objectId)
                    assertTrue(model.title.startsWith("New Zealand"))
                    val root = fragment.requireView()
                    val image =
                        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    File(target.getExternalFilesDir(null), "plaza-figma-compose.png")
                        .outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                // Exercise the real state collector and WitStudio alert without uploading a file.
                scenario.onActivity { activity ->
                    val fragment =
                        activity.supportFragmentManager.fragments.firstNotNullOfOrNull(::compose)!!
                    val model = ViewModelProvider(fragment)[PlazaComposeViewModel::class.java]
                    model.attach((1..10).map { Uri.parse("content://plaza-local-test/$it") })
                }
                instrumentation.waitForIdleSync()
                val alertDeadline = android.os.SystemClock.uptimeMillis() + 5000
                var alertVisible = false
                while (!alertVisible && android.os.SystemClock.uptimeMillis() < alertDeadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    alertVisible =
                        root
                            ?.findAccessibilityNodeInfosByText(
                                target.getString(R.string.plaza_photo_limit, 9)
                            )
                            ?.any { it.isVisibleToUser } == true
                    if (!alertVisible) android.os.SystemClock.sleep(50)
                }
                assertTrue("The composer must present its error as a visible alert", alertVisible)
                assertTrue(
                    instrumentation.uiAutomation.rootInActiveWindow
                        .findAccessibilityNodeInfosByText(
                            target.getString(R.string.plaza_understood)
                        )
                        .any { it.isVisibleToUser }
                )
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
        } finally {
            fixtures.forEach { it.delete() }
        }
    }
}
