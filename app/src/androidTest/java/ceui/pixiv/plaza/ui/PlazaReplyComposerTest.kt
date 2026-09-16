package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.session.SessionManager
import ceui.pixiv.feeds.cache.feedFirstPageCache
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaPage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads an existing post and edits only a local draft; never sends a reply or reaction. */
@RunWith(AndroidJUnit4::class)
class PlazaReplyComposerTest {
    @Test
    fun roomSnapshotDisplaysBeforeNetworkAndRemainsAfterOfflineFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uid = SessionManager.loggedInUid
        val page = runBlocking { PlazaRepository.api.feed() }
        assertTrue(page.items.isNotEmpty())
        val cache = feedFirstPageCache(
            "plaza-instrumentation", PlazaPage::class.java, accountId = { uid },
        )
        val network = CompletableDeferred<PlazaPage>()
        val api = object : PlazaApi by PlazaRepository.api {
            override suspend fun feed(before: Long?, limit: Int, author: Long?, replyTo: Long?) =
                network.await()
        }
        val models = ViewModelStore()
        lateinit var model: PlazaTimelineViewModel
        try {
            runBlocking { cache.write(page, page.nextBefore?.toString()) }
            instrumentation.runOnMainSync {
                model = PlazaTimelineViewModel(SavedStateHandle(), api, { uid }, { _, _ -> cache })
                models.put("timeline", model)
                model.enter()
            }
            fun awaitState(check: (TimelineState) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = check(model.state.value) }
                    if (ready) return
                    SystemClock.sleep(50)
                }
                fail("Room first-page restore did not reach the expected state")
            }
            awaitState { it.loading && it.items.map { post -> post.id } == page.items.map { it.id } }
            network.completeExceptionally(IOException("offline test"))
            awaitState { !it.loading && it.error != null && it.items.isNotEmpty() }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            runBlocking { cache.clear() }
        }
    }

    @Test
    fun inlinePanelKeyboardBackAndDraftRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val post = runBlocking { PlazaRepository.api.feed().items.first() }
        // Match list navigation: the complete post has already been placed in ObjectPool.
        instrumentation.runOnMainSync { PlazaRepository.cache(post, SessionManager.loggedInUid) }
        val intent = Intent(instrumentation.targetContext, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_POST_DETAIL.key)
            .putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, post.id)
        ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
            fun awaitState(check: (PlazaReplyBar) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { activity ->
                        findRefresh(activity.window.decorView)?.let {
                            assertFalse("Automatic comment loading must not animate pull-to-refresh", it.isRefreshing)
                        }
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

    private fun findRefresh(view: View): SwipeRefreshLayout? = when (view) {
        is SwipeRefreshLayout -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findRefresh(view.getChildAt(it)) }
        else -> null
    }
}
