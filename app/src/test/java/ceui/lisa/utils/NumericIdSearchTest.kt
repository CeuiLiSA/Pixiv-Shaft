package ceui.lisa.utils

import android.app.Application
import android.app.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.models.IllustSearchResponse
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NumericIdSearchTest {
    private lateinit var controller: ActivityController<FragmentActivity>
    private var originalApi: API? = null
    private val mainDispatcher = StandardTestDispatcher()
    private val response = CompletableDeferred<IllustSearchResponse>()
    private val requestStarted = CountDownLatch(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        originalApi = ReflectionHelpers.getField(Client, "_appApi")
        val lookup: suspend () -> IllustSearchResponse = {
            requestStarted.countDown()
            response.await()
        }
        val api = Proxy.newProxyInstance(API::class.java.classLoader, arrayOf(API::class.java)) { _, method, args ->
            check(method.name == "getIllustByID")
            @Suppress("UNCHECKED_CAST")
            lookup.startCoroutineUninterceptedOrReturn(args.last() as Continuation<IllustSearchResponse>)
        } as API
        ReflectionHelpers.setField(Client, "_appApi", api)
        controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
    }

    @After
    fun tearDown() {
        response.cancel()
        controller.pause().stop().destroy()
        ReflectionHelpers.setField(Client, "_appApi", originalApi)
        Dispatchers.resetMain()
    }

    @Test
    fun `destroying the search view cancels lookup and dismisses the window`() = runTest(timeout = 10.seconds) {
        val owner = SearchView()
        val (dialog, request) = startSearch(owner)

        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        request.join()

        assertTrue(request.isCancelled)
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun `late lookup failure after the window is removed does not dismiss again or navigate`() = runTest(timeout = 10.seconds) {
        val activity = controller.get()
        val (dialog, request) = startSearch(SearchView())
        val window = requireNotNull(dialog.window)
        window.windowManager.removeViewImmediate(window.decorView)
        assertNull(window.decorView.parent)
        assertTrue(dialog.isShowing)

        response.completeExceptionally(IOException("lookup failed"))
        request.join()

        assertFalse(request.isCancelled)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun startSearch(owner: LifecycleOwner): Pair<Dialog, Job> {
        searchNumericId(controller.get(), owner, "12345", null)
        mainDispatcher.scheduler.runCurrent()
        val request = owner.lifecycleScope.coroutineContext.job.children.single()
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        return dialog to request
    }

    private class SearchView : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }
}
