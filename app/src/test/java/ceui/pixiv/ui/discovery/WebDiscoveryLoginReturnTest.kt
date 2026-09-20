package ceui.pixiv.ui.discovery

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.ui.common.IllustFeedItem
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDiscoveryLoginReturnTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ActivityController<FragmentActivity>

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        Dispatchers.resetMain()
    }

    private fun attach(viewModel: FeedViewModel<String>): WebDiscoveryFeedFragment {
        val fragment = WebDiscoveryFeedFragment.newInstance(WebDiscoveryMode.ALL)
        controller.get().supportFragmentManager.beginTransaction()
            .add(fragment, "discovery")
            // Keep the tab offscreen, including after recreation; no list view is needed here.
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            .commitNow()
        fragment.viewModelStore.put(FeedViewModel::class.java.name, viewModel)
        controller.start().resume()
        return fragment
    }

    @Test fun `login refresh survives recreation before the visited tab becomes visible`() = runTest(dispatcher) {
        var loggedIn = false
        var requests = 0
        val viewModel = FeedViewModel(FeedSource<String> {
            requests++
            FeedPage(if (loggedIn) listOf(IllustFeedItem(Illust(id = 42))) else emptyList(), null)
        }, autoLoad = false)
        val fragment = attach(viewModel)
        viewModel.ensureLoaded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasLoadedOnce)
        assertTrue(viewModel.uiState.value.items.isEmpty())

        loggedIn = true
        fragment.onWebLoginReturned()
        controller.recreate()
        advanceUntilIdle()

        val restored = controller.get().supportFragmentManager.findFragmentByTag("discovery")
            as WebDiscoveryFeedFragment
        assertNotSame(fragment, restored)
        assertSame(viewModel, restored.viewModelStore[FeedViewModel::class.java.name])
        assertEquals(2, requests)
        assertEquals(listOf(42L), viewModel.uiState.value.items.map { it.feedKey })
    }

    @Test fun `login return clears stale rows in a visited offscreen tab before refreshing`() = runTest(dispatcher) {
        var id = 1L
        val viewModel = FeedViewModel(FeedSource<String> {
            FeedPage(listOf(IllustFeedItem(Illust(id = id))), null)
        }, autoLoad = false)
        val fragment = attach(viewModel)
        viewModel.ensureLoaded()
        advanceUntilIdle()

        id = 2L
        fragment.onWebLoginReturned()
        assertTrue(viewModel.uiState.value.items.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.items.map { it.feedKey })
    }

    @Test fun `login return does not fetch tabs that have never been visited`() = runTest(dispatcher) {
        var requests = 0
        val viewModel = FeedViewModel(FeedSource<String> {
            requests++
            FeedPage(emptyList(), null)
        }, autoLoad = false)
        attach(viewModel).onWebLoginReturned()
        advanceUntilIdle()
        assertEquals(0, requests)
        assertFalse(viewModel.uiState.value.hasLoadedOnce)
    }

    @Test fun `login return retries an offscreen tab whose first request failed`() = runTest(dispatcher) {
        var loggedIn = false
        val viewModel = FeedViewModel(FeedSource<String> {
            if (!loggedIn) throw IOException("Login expired")
            FeedPage(listOf(IllustFeedItem(Illust(id = 42))), null)
        }, autoLoad = false)
        val fragment = attach(viewModel)
        viewModel.ensureLoaded()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasLoadedOnce)

        loggedIn = true
        fragment.onWebLoginReturned()
        advanceUntilIdle()
        assertEquals(listOf(42L), viewModel.uiState.value.items.map { it.feedKey })
    }

    @Test fun `login return cancels an old session request in an offscreen tab`() = runTest(dispatcher) {
        var requests = 0
        var oldRequestCancelled = false
        val pending = CompletableDeferred<Unit>()
        val viewModel = FeedViewModel(FeedSource<String> {
            if (++requests == 1) {
                try {
                    pending.await()
                } finally {
                    oldRequestCancelled = true
                }
            }
            FeedPage(listOf(IllustFeedItem(Illust(id = 42))), null)
        }, autoLoad = false)
        val fragment = attach(viewModel)
        viewModel.ensureLoaded()
        runCurrent()

        fragment.onWebLoginReturned()
        advanceUntilIdle()
        assertTrue(oldRequestCancelled)
        assertEquals(2, requests)
        assertEquals(listOf(42L), viewModel.uiState.value.items.map { it.feedKey })
    }
}
