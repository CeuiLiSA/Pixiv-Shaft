package ceui.pixiv.chat.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.chat.vm.ChatListViewModel
import ceui.pixiv.ui.navigation.TemplateRoute
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSharedViewerTest {
    @Test
    fun sendsThreePhotosAndUsesTheirDisplayedOrientation() {
        val arguments = InstrumentationRegistry.getArguments()
        val peer = arguments.getString("photoTestPeer")?.toLongOrNull()
        assumeTrue("Requires explicit permission to send to the test chat", peer != null && arguments.getString("photoTestSend") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "images").apply { mkdirs() }
        val files = listOf(Color.BLUE, Color.MAGENTA, Color.GREEN).mapIndexed { index, color ->
            File.createTempFile("chat-test-", ".jpg", directory).also { file ->
                val bitmap = if (index == 0) Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                else Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                if (index == 0) ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    saveAttributes()
                }
            }
        }
        try {
            val intent = Intent(target, TemplateActivity::class.java)
                .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key)
                .putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, checkNotNull(peer))
            ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
                var model: ChatListViewModel? = null
                var oldKey: String? = null
                val connectDeadline = SystemClock.uptimeMillis() + 15_000
                while (model == null && SystemClock.uptimeMillis() < connectDeadline) {
                    scenario.onActivity { activity ->
                        if (activity.findViewById<View>(R.id.btn_photo)?.isEnabled != true) return@onActivity
                        val fragment = activity.supportFragmentManager.fragments.filterIsInstance<DemoChatListFragment>().single()
                        model = ViewModelProvider(fragment)[ChatListViewModel::class.java].also {
                            oldKey = it.messages.value.firstOrNull()?.localKey
                            val uris = files.map { file -> FileProvider.getUriForFile(target, "${target.packageName}.provider", file) }
                            it.selectPhotos(target.contentResolver, uris, "[Photo layout test]")
                        }
                    }
                    if (model == null) SystemClock.sleep(100)
                }
                val chat = checkNotNull(model) { "The test chat should establish a connection" }
                val description = target.getString(R.string.plaza_image_accessibility, 1, 3)
                val deadline = SystemClock.uptimeMillis() + 25_000
                var displayed = false
                while (!displayed && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val sent = chat.messages.value.firstOrNull()
                        val photo = findImage(activity.window.decorView, description)
                        displayed = chat.photoState.value == ChatListViewModel.PhotoState.Idle &&
                            sent?.localKey != oldKey && sent?.hasAttachment == true &&
                            photo?.drawable != null && photo.width == (photo.parent as ChatAttachmentView).width
                    }
                    if (!displayed) SystemClock.sleep(100)
                }
                assertTrue("The acknowledged album should show the rotated landscape photo across the full first row", displayed)
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun opensOriginalAndPagesThroughPrivateAttachments() {
        val arguments = InstrumentationRegistry.getArguments()
        val peer = arguments.getString("photoTestPeer")?.toLongOrNull()
        assumeTrue("Requires a signed-in device and a private chat with test photos", peer != null)
        val count = arguments.getString("photoTestCount")?.toIntOrNull() ?: 9
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val expected = target.getString(R.string.plaza_image_accessibility, 1, count)
        val intent = Intent(target, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key)
            .putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, checkNotNull(peer))
        ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
            var thumbnail: View? = null
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (thumbnail == null && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    thumbnail = findImage(activity.window.decorView, expected)?.takeIf { image ->
                        val album = image.parent as ChatAttachmentView
                        image.isShown && (0 until album.childCount).all { (album.getChildAt(it) as ImageView).drawable != null }
                    }
                }
                if (thumbnail == null) SystemClock.sleep(100)
            }
            val first = checkNotNull(thumbnail) { "The test message should show its first photo" }
            instrumentation.waitForIdleSync()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && arguments.getString("captureTestAlbum") == "true") {
                captureAlbum(scenario, first)
            }
            instrumentation.runOnMainSync {
                val album = first.parent as ChatAttachmentView
                assertTrue(album.clipToOutline)
                assertEquals(count, album.childCount)
                for (index in 0 until count) assertFalse(album.getChildAt(index).clipToOutline)
                assertTrue(first.performClick())
            }

            var viewer: ImageDetailActivity? = null
            val viewerDeadline = SystemClock.uptimeMillis() + 10_000
            while (viewer == null && SystemClock.uptimeMillis() < viewerDeadline) {
                instrumentation.runOnMainSync {
                    viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ImageDetailActivity>().firstOrNull()
                }
                if (viewer == null) SystemClock.sleep(100)
            }

            val activity = checkNotNull(viewer) { "Photo should open the shared full-screen viewer" }
            val source = checkNotNull(activity.plazaImageSource)
            assertEquals(count, source.size)
            assertTrue(runBlocking { source.file(target, 0).length() > 0 })
            if (count > 1) {
                instrumentation.runOnMainSync {
                    val pager = activity.findViewById<ViewPager>(R.id.view_pager)
                    pager.setCurrentItem(1, false)
                    assertEquals(1, pager.currentItem)
                }
                assertTrue(runBlocking { source.file(target, 1).length() > 0 })
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun captureAlbum(scenario: ActivityScenario<TemplateActivity>, first: View) {
        val saved = CountDownLatch(1)
        scenario.onActivity { activity ->
            val album = first.parent as ChatAttachmentView
            val bounds = Rect()
            if (!album.getGlobalVisibleRect(bounds)) return@onActivity
            val screenshot = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bounds, screenshot, { result ->
                if (result == PixelCopy.SUCCESS) {
                    File(activity.getExternalFilesDir(null), "chat-test-album.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                screenshot.recycle()
                saved.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        saved.await(3, TimeUnit.SECONDS)
    }

    private fun findImage(view: View, description: String): ImageView? {
        if (view is ImageView && view.contentDescription == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findImage(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }
}
