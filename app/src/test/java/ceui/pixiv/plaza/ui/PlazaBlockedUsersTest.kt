package ceui.pixiv.plaza.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.plaza.*
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.navigation.TemplateRouteFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlazaBlockedUsersTest {
    @Test fun `blocked users entry opens its own page`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try {
            val activity = controller.get()
            activity.showPlazaModeration(0L, 0L, "blocks")
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(TemplateRoute.PLAZA_BLOCKS.key, intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            val fragment = TemplateRouteFactory.create(TemplateRoute.PLAZA_BLOCKS, intent)
            assertTrue(fragment is PlazaBlockedUsersFragment)
            assertTrue(fragment is ceui.pixiv.feeds.FeedFragment)
            assertEquals("blocks", fragment.arguments!!.getString("mode"))
            assertTrue(activity.supportFragmentManager.fragments.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private val unused = Proxy.newProxyInstance(PlazaApi::class.java.classLoader, arrayOf(PlazaApi::class.java)) { _, _, _ -> error("Unexpected API") } as PlazaApi

    @Test fun `restored legacy block list redirects once and removes its dialog`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup()
        try {
            val manager = activity.get().supportFragmentManager
            PlazaModerationDialog().apply {
                arguments = androidx.core.os.bundleOf("mode" to "blocks")
            }.showNow(manager, "legacy-blocks")
            manager.executePendingTransactions()
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertEquals(TemplateRoute.PLAZA_BLOCKS.key, intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            assertNull(manager.findFragmentByTag("legacy-blocks"))
            activity.recreate()
            assertNull(shadowOf(activity.get()).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun `page recreation retains its feed and completes an in-flight unblock`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup().visible()
        try {
            val response = CompletableDeferred<DeletePost>()
            var fetches = 0
            val api = object : PlazaApi by unused {
                override suspend fun blocks(): PlazaBlocks {
                    fetches++
                    return PlazaBlocks(listOf(PlazaBlockedUser(99L, "User")))
                }
                override suspend fun unblock(uid: Long) = response.await()
            }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { 42L }, {})
            val fragment = PlazaBlockedUsersFragment()
            val manager = activity.get().supportFragmentManager
            manager.beginTransaction().add(android.R.id.content, fragment, "blocks")
                .setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.CREATED).commitNow()
            val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = controller as T
            }
            androidx.lifecycle.ViewModelProvider(fragment, factory)[PlazaBlocksController::class.java]
            manager.beginTransaction().setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.RESUMED).commitNow()
            runCurrent()
            val feed = androidx.lifecycle.ViewModelProvider(fragment)[ceui.pixiv.feeds.FeedViewModel::class.java.name, ceui.pixiv.feeds.FeedViewModel::class.java]
            assertEquals(1, feed.uiState.value.items.size)
            controller.unblock(99L, feed); runCurrent()
            activity.recreate(); runCurrent()
            val restored = activity.get().supportFragmentManager.findFragmentByTag("blocks")!!
            val restoredFeed = androidx.lifecycle.ViewModelProvider(restored)[ceui.pixiv.feeds.FeedViewModel::class.java.name, ceui.pixiv.feeds.FeedViewModel::class.java]
            assertSame(feed, restoredFeed)
            assertTrue((restoredFeed.uiState.value.items.single() as PlazaBlockedUserItem).busy)
            response.complete(DeletePost(true)); runCurrent()
            assertEquals(1, fetches)
            assertTrue(restoredFeed.uiState.value.showEmptyState)
            assertNotNull(restored.requireView().findViewById<View>(ceui.pixiv.feeds.R.id.feed_list_view))
        } finally {
            activity.pause().stop().destroy()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `leaving during unblock cancels the request and invalidates its original account cache`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val response = CompletableDeferred<DeletePost>()
            val owners = mutableListOf<Long>()
            var account = 42L
            var cancelled = false
            val api = object : PlazaApi by unused {
                override suspend fun blocks() = PlazaBlocks(listOf(PlazaBlockedUser(99L, "User")))
                override suspend fun unblock(uid: Long): DeletePost = try {
                    response.await()
                } finally { cancelled = true }
            }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { account }, { owners += it })
            val feed = ceui.pixiv.feeds.FeedViewModel(controller, autoLoad = false)
            store.put("controller", controller); store.put("feed", feed)
            feed.refresh(); runCurrent()
            controller.unblock(99L, feed); runCurrent()
            account = 77L
            store.clear(); runCurrent()
            assertTrue(cancelled)
            assertEquals(listOf(42L), owners)
            assertNull(controller.error.value)
            assertFalse((feed.uiState.value.items.single() as PlazaBlockedUserItem).busy)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `feed owns loading errors retry and confirmed unblock without another fetch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val response = CompletableDeferred<DeletePost>()
            var fetches = 0
            var deletes = 0
            val owners = mutableListOf<Long>()
            val api = object : PlazaApi by unused {
                override suspend fun blocks(): PlazaBlocks {
                    if (++fetches == 1) throw IOException("offline")
                    return PlazaBlocks(listOf(PlazaBlockedUser(99L, "User")))
                }
                override suspend fun unblock(uid: Long): DeletePost {
                    assertEquals(99L, uid)
                    deletes++
                    return response.await()
                }
            }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { 42L }, { owners += it })
            val feed = ceui.pixiv.feeds.FeedViewModel(controller, autoLoad = false)
            store.put("controller", controller); store.put("feed", feed)
            feed.refresh(); runCurrent()
            assertTrue(feed.uiState.value.showFullscreenError)
            feed.refresh(); runCurrent()
            assertEquals(1, feed.uiState.value.items.size)
            assertTrue(feed.uiState.value.reachedEnd)
            controller.unblock(99L, feed); controller.unblock(99L, feed); runCurrent()
            assertEquals(1, deletes)
            assertTrue((feed.uiState.value.items.single() as PlazaBlockedUserItem).busy)
            response.complete(DeletePost(true)); runCurrent()
            assertTrue(feed.uiState.value.items.isEmpty())
            assertEquals(2, fetches)
            assertEquals(listOf(42L), owners)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `refresh during unblock waits for mutation and does not restore stale users`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val response = CompletableDeferred<DeletePost>()
            var deleted = false
            var fetches = 0
            val api = object : PlazaApi by unused {
                override suspend fun blocks(): PlazaBlocks {
                    fetches++
                    return PlazaBlocks(if (deleted) emptyList() else listOf(PlazaBlockedUser(99L, "User")))
                }
                override suspend fun unblock(uid: Long): DeletePost {
                    val result = response.await()
                    deleted = true
                    return result
                }
            }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { 42L }, {})
            val feed = ceui.pixiv.feeds.FeedViewModel(controller, autoLoad = false)
            store.put("controller", controller); store.put("feed", feed)
            feed.refresh(); runCurrent()
            controller.unblock(99L, feed); runCurrent()
            feed.refresh(); runCurrent()
            assertEquals(1, fetches)
            response.complete(DeletePost(true)); runCurrent()
            assertEquals(2, fetches)
            assertTrue(feed.uiState.value.items.isEmpty())
            assertNull(controller.error.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `failed unblock keeps the row enabled for retry and invalidates safety cache`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val owners = mutableListOf<Long>()
            var attempts = 0
            val api = object : PlazaApi by unused {
                override suspend fun blocks() = PlazaBlocks(listOf(PlazaBlockedUser(99L, "User")))
                override suspend fun unblock(uid: Long): DeletePost {
                    if (++attempts == 1) throw IOException("lost response")
                    return DeletePost(true)
                }
            }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { 42L }, { owners += it })
            val feed = ceui.pixiv.feeds.FeedViewModel(controller, autoLoad = false)
            store.put("controller", controller); store.put("feed", feed)
            feed.refresh(); runCurrent()
            controller.unblock(99L, feed); runCurrent()
            assertFalse((feed.uiState.value.items.single() as PlazaBlockedUserItem).busy)
            assertNotNull(controller.error.value)
            controller.unblock(99L, feed); runCurrent()
            assertTrue(feed.uiState.value.items.isEmpty())
            assertEquals(listOf(42L, 42L), owners)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `account change while loading cannot show another accounts block list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var account = 42L
            val response = CompletableDeferred<PlazaBlocks>()
            val api = object : PlazaApi by unused { override suspend fun blocks() = response.await() }
            val controller = PlazaBlocksController(SavedStateHandle(), api, { account }, {})
            val feed = ceui.pixiv.feeds.FeedViewModel(controller, autoLoad = false)
            store.put("controller", controller); store.put("feed", feed)
            feed.refresh(); runCurrent()
            account = 77L
            response.complete(PlazaBlocks(listOf(PlazaBlockedUser(99L, "Hidden")))); runCurrent()
            assertTrue(feed.uiState.value.items.isEmpty())
            assertTrue(feed.uiState.value.showFullscreenError)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `user row shows identity and unblock action`() = verifyRow(1f)

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w320dp-h640dp-night")
    fun `narrow dark rows wrap long names and large text`() = verifyRow(2f)

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test @Config(qualifiers = "w840dp-h1000dp")
    fun `wide rows preserve the same actions`() = verifyRow(1f)

    private fun verifyRow(fontScale: Float) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val ctx = controller.get()
        @Suppress("DEPRECATION")
        ctx.resources.updateConfiguration(android.content.res.Configuration(ctx.resources.configuration).apply { this.fontScale = fontScale }, ctx.resources.displayMetrics)
        ctx.setTheme(R.style.AppTheme)
        controller.setup()
        try {
            var removed: Long? = null
            val user = PlazaBlockedUser(99L, "很长的用户名 Long display name ".repeat(8))
            val row = PlazaBlockedUserView(ctx)
            row.bind(PlazaBlockedUserItem(user)) { removed = it }
            val width = minOf(ctx.resources.displayMetrics.widthPixels, ctx.dp(720)) - ctx.dp(40)
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, width, row.measuredHeight)
            val texts = children(row).filterIsInstance<TextView>()
            val name = texts.first()
            assertEquals(user.displayName, name.text.toString())
            assertTrue(name.lineCount > 1)
            assertTrue(name.height >= name.layout.height)
            val action = texts.single { it.isClickable }
            assertTrue(action.height >= ctx.dp(48))
            // 胶囊有行宽上限：大字体下它自己换行，用户名列不会被挤成一列单字。
            assertTrue("name column ${name.width}px", name.width >= ctx.dp(64))
            action.performClick()
            assertEquals(99L, removed)
            row.bind(PlazaBlockedUserItem(user, busy = true)) { removed = it }
            assertFalse(action.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
}
