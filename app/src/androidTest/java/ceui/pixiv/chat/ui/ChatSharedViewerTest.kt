package ceui.pixiv.chat.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSharedViewerTest {
    @Test
    fun sendsSamplePhotosAndUsesTheirDisplayedOrientation() {
        val arguments = InstrumentationRegistry.getArguments()
        val peer = arguments.getString("photoTestPeer")?.toLongOrNull()
        assumeTrue("Requires explicit permission to send to the test chat", peer != null && arguments.getString("photoTestSend") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "images").apply { mkdirs() }
        val messageText = "[Photo layout test ${UUID.randomUUID()}]"
        val pair = arguments.getString("photoTestCount") == "2"
        val colors = if (pair) listOf(0xFF21599D.toInt(), 0xFF267E74.toInt()) else listOf(Color.BLUE, Color.MAGENTA, Color.GREEN)
        val files = colors.mapIndexed { index, color ->
            File.createTempFile("chat-test-", ".jpg", directory).also { file ->
                val bitmap = if (pair || index == 0) Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                else Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                if (pair) Canvas(bitmap).drawText("${index + 1}", 180f, 349f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = 88f
                })
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                if (!pair && index == 0) ExifInterface(file.absolutePath).apply {
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
                val connectDeadline = SystemClock.uptimeMillis() + 15_000
                while (model == null && SystemClock.uptimeMillis() < connectDeadline) {
                    scenario.onActivity { activity ->
                        if (activity.findViewById<View>(R.id.btn_photo)?.isEnabled != true) return@onActivity
                        val fragment = activity.supportFragmentManager.fragments.filterIsInstance<DemoChatListFragment>().single()
                        model = ViewModelProvider(fragment)[ChatListViewModel::class.java].also {
                            val uris = files.map { file -> FileProvider.getUriForFile(target, "${target.packageName}.provider", file) }
                            it.selectPhotos(target.contentResolver, uris, messageText)
                        }
                    }
                    if (model == null) SystemClock.sleep(100)
                }
                val chat = checkNotNull(model) { "The test chat should establish a connection" }
                val description = target.getString(R.string.plaza_image_accessibility, 1, files.size)
                val deadline = SystemClock.uptimeMillis() + 25_000
                var displayed = false
                while (!displayed && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val sent = chat.messages.value.firstOrNull { it.text == messageText && it.hasAttachment }
                            ?: return@onActivity
                        val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
                        val adapter = (list.adapter as ConcatAdapter).adapters.first() as ChatMessageAdapter
                        val position = adapter.positionOf(sent.localKey)
                        val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@onActivity
                        val photo = findImage(row, description)
                        val album = photo?.parent as? ChatAttachmentView
                        displayed = chat.photoState.value == ChatListViewModel.PhotoState.Idle &&
                            photo?.drawable != null && album != null &&
                            if (pair) album.getChildAt(1).top == photo.top && photo.width < album.width
                            else photo.width == album.width
                    }
                    if (!displayed) SystemClock.sleep(100)
                }
                assertTrue("The acknowledged album should use the expected row layout", displayed)
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
        val capture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && arguments.getString("captureTestAlbum") == "true"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val expected = target.getString(R.string.plaza_image_accessibility, 1, count)
        val intent = Intent(target, TemplateActivity::class.java)
            .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key)
            .putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, checkNotNull(peer))
        ActivityScenario.launch<TemplateActivity>(intent).use { scenario ->
            var thumbnail: View? = null
            var nextPosition = 1
            var nextScroll = SystemClock.uptimeMillis() + 1_000
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (thumbnail == null && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val candidate = findImage(activity.window.decorView, expected)
                    thumbnail = candidate?.takeIf { image ->
                        val album = image.parent as ChatAttachmentView
                        image.isShown && (!capture || isFullyVisible(album)) &&
                            (0 until album.childCount).all { (album.getChildAt(it) as ImageView).drawable != null }
                    }
                    if (thumbnail == null && SystemClock.uptimeMillis() >= nextScroll) {
                        val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
                        if (candidate != null && capture && !isFullyVisible(candidate.parent as View)) {
                            val position = list.findContainingViewHolder(candidate)?.absoluteAdapterPosition
                            if (position != null && position != RecyclerView.NO_POSITION) {
                                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                            }
                        } else if (candidate == null && nextPosition < (list.adapter?.itemCount ?: 0)) {
                            list.scrollToPosition(nextPosition++)
                        }
                        nextScroll = SystemClock.uptimeMillis() + 500
                    }
                }
                if (thumbnail == null) SystemClock.sleep(100)
            }
            val first = checkNotNull(thumbnail) { "The test message should show its first photo" }
            instrumentation.waitForIdleSync()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && capture) {
                captureAlbum(scenario, first, count)
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
    private fun captureAlbum(scenario: ActivityScenario<TemplateActivity>, first: View, count: Int) {
        val saved = CountDownLatch(1)
        val captured = AtomicBoolean()
        scenario.onActivity { activity ->
            val album = first.parent as ChatAttachmentView
            val bounds = Rect()
            if (!album.getGlobalVisibleRect(bounds)) return@onActivity
            val screenshot = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bounds, screenshot, { result ->
                if (result == PixelCopy.SUCCESS) {
                    File(activity.getExternalFilesDir(null), "chat-test-album-$count.png").outputStream().use {
                        captured.set(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                screenshot.recycle()
                saved.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Expected a fresh screenshot of the entire album", saved.await(3, TimeUnit.SECONDS) && captured.get())
    }

    private fun isFullyVisible(view: View): Boolean {
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.width() == view.width && bounds.height() == view.height
    }

    private fun findImage(view: View, description: String): ImageView? {
        if (view is ImageView && view.contentDescription == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findImage(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }
}
