package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads an existing post and edits only a local draft; never sends a reply or reaction. */
@RunWith(AndroidJUnit4::class)
class PlazaReplyComposerTest {
    @Test
    fun inlinePanelKeyboardBackAndDraftRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val post = runBlocking { PlazaRepository.api.feed().items.first() }
        val intent = Intent(instrumentation.targetContext, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_POST_DETAIL.key)
            .putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, post.id)
        ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
            fun awaitState(check: (PlazaReplyBar) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { activity ->
                        findBar(activity.window.decorView)?.let { ready = check(it) }
                    }
                    if (ready) return
                    SystemClock.sleep(50)
                }
                fail("Reply composer did not reach the expected state")
            }
            fun withBar(action: (PlazaReplyBar) -> Unit) {
                scenario.onActivity { action(checkNotNull(findBar(it.window.decorView))) }
            }
            fun screenshot(name: String) {
                SystemClock.sleep(500) // Allow the IME surface animation to finish before capture.
                instrumentation.waitForIdleSync()
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(instrumentation.targetContext.getExternalFilesDir(null), name)
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            awaitState { it.composer.etInput.isEnabled }
            screenshot("plaza-reply-screen.png")
            withBar {
                it.composer.etInput.setText("Local reply draft 本地草稿")
                assertTrue(it.composer.btnSend.isEnabled)
                it.composer.btnEmoji.performClick()
            }
            awaitState { it.emojiPanel.isShown && it.emojiPanel.height > it.context.dp(200) }
            withBar { it.composer.etInput.performClick() }
            awaitState {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    it.emojiPanel.visibility == View.GONE
            }
            screenshot("plaza-reply-keyboard.png")
            withBar { it.composer.btnEmoji.performClick() }
            awaitState {
                it.emojiPanel.isShown && it.emojiPanel.childCount > 0 &&
                    ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            screenshot("plaza-reply-panel.png")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitState { it.emojiPanel.visibility == View.GONE }
            scenario.recreate()
            awaitState { it.composer.etInput.isEnabled }
            withBar {
                assertEquals("Local reply draft 本地草稿", it.composer.etInput.text.toString())
                assertTrue(it.composer.btnSend.isEnabled)
                it.composer.etInput.text?.clear()
                assertFalse(it.composer.btnSend.isEnabled)
            }
        }
    }

    private fun findBar(view: View): PlazaReplyBar? = when (view) {
        is PlazaReplyBar -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findBar(view.getChildAt(it)) }
        else -> null
    }
}
