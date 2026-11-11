package ceui.lisa.update

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import com.hjq.toast.Toaster
import kotlinx.coroutines.CompletableDeferred
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpgradeRequiredGithubTest {
    private val previousSettings = Shaft.sSettings
    private val messages = mutableListOf<String>()
    private val api = DeferredApi()

    @Before
    fun setUp() {
        assumeFalse(BuildConfig.IS_LITE)
        Shaft.sSettings = Settings().apply { githubProxy = "https://example.invalid" }
        AppUpdateChecker.apiOverride = api
        Toaster.init(RuntimeEnvironment.getApplication())
        Toaster.setInterceptor { messages.add(it.text.toString()); true }
    }

    @After
    fun tearDown() {
        AppUpdateChecker.apiOverride = null
        Shaft.sSettings = previousSettings
        Toaster.setInterceptor(null)
    }

    @Test
    fun `a completed check waits for foreground and is delivered once`() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            UpgradeRequiredPrompt.open(activity)
            await { api.calls.get() == 1 }
            controller.pause().stop()
            api.results[0].complete(currentRelease())
            await { api.returned.get() == 1 }
            assertEquals(0, resultCount())
            controller.start().resume()
            await { resultCount() == 1 }
            controller.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, resultCount())
        }
    }

    @Test
    fun `a new screen can take over without a stale result on the previous screen`() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { first ->
            UpgradeRequiredPrompt.open(first.get())
            await { api.calls.get() == 1 }
            first.pause().stop()
            api.results[0].complete(currentRelease())
            await { api.returned.get() == 1 }
            Robolectric.buildActivity(FragmentActivity::class.java).setup().use { second ->
                UpgradeRequiredPrompt.open(second.get())
                await { api.calls.get() == 2 }
                api.results[1].complete(currentRelease())
                await { resultCount() == 1 }
            }
            first.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, resultCount())
        }
    }

    private fun resultCount() = messages.count {
        it == RuntimeEnvironment.getApplication().getString(R.string.upgrade_required_no_update)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for update delivery", condition())
    }

    private fun currentRelease() = GitHubRelease("v0.0.0", null, null, null, null, emptyList())

    private class DeferredApi : GitHubApi {
        val calls = AtomicInteger()
        val returned = AtomicInteger()
        val results = List(2) { CompletableDeferred<GitHubRelease>() }
        override suspend fun getLatestRelease(owner: String, repo: String): GitHubRelease {
            val release = results[calls.getAndIncrement()].await()
            returned.incrementAndGet()
            return release
        }
        override suspend fun getReleases(owner: String, repo: String, perPage: Int, page: Int): List<GitHubRelease> = error("unexpected history request")
        override suspend fun getReleaseByTag(owner: String, repo: String, tag: String): GitHubRelease = error("unexpected release lookup")
        override suspend fun getReleasesAtom(owner: String, repo: String): ResponseBody = error("unexpected Atom request")
    }
}
