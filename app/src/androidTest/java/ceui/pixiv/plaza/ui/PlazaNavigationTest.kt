package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/** Exercises navigation and local drafts only; never publishes, deletes or reacts. */
@RunWith(AndroidJUnit4::class)
class PlazaNavigationTest {
    @Test
    fun sharedToolbarAndSystemBackOnReadPages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (route in listOf(TemplateRoute.PLAZA, TemplateRoute.PLAZA_POST_DETAIL)) {
            val intent = Intent(context, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
                .putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, 1L)
            ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    // The feed's single action is the compose FAB; only the detail has a menu.
                    assertToolbar(activity, menuItems = if (route == TemplateRoute.PLAZA) 0 else 1)
                    assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                    val root = activity.findViewById<android.view.View>(R.id.fragment_container)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "plaza-toolbar-${route.name}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun emptyComposerReleasesBackAfterEditsAreClearedAndRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_COMPOSE.key)
        ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertToolbar(activity)
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                val text = activity.findViewById<EditText>(R.id.plaza_draft_text)
                text.setText("draft")
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                text.setText("")
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                val title = activity.findViewById<EditText>(R.id.plaza_draft_title)
                title.setText("restored draft")
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                activity.findViewById<EditText>(R.id.plaza_draft_title).setText("")
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<PlazaComposeFragment>().single()
                ViewModelProvider(fragment)[PlazaComposeViewModel::class.java].reference(123, "illust")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<PlazaComposeFragment>().single()
                ViewModelProvider(fragment)[PlazaComposeViewModel::class.java].reference(null, null)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            }
        }
    }

    private fun assertToolbar(activity: TemplateActivity, menuItems: Int = 1) {
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        assertNotNull(toolbar)
        val primary = TypedValue()
        activity.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, primary, true)
        assertEquals(primary.data, (toolbar.background as ColorDrawable).color)
        val statusBar = ViewCompat.getRootWindowInsets(toolbar)!!
            .getInsets(WindowInsetsCompat.Type.statusBars()).top
        assertEquals(statusBar, toolbar.paddingTop)
        assertEquals(0, toolbar.paddingBottom)
        assertEquals(menuItems, toolbar.menu.size())
    }
}
