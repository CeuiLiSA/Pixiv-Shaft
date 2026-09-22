package ceui.pixiv.ui.debug

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelStore
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the actual probe, including logging order, lifecycle and its informational status. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkCdnTraceProbeTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    private val store = ViewModelStore()
    private lateinit var vm: NetworkTestViewModel
    private lateinit var cfg: Any
    private var oldContext: Context? = null
    private var oldSettings: Settings? = null
    private val clients = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        oldContext = Shaft.getContext()
        oldSettings = Shaft.sSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        Shaft.sSettings = Settings()
        vm = NetworkTestViewModel()
        store.put("network", vm)
        cfg = ReflectionHelpers.callInstanceMethod(vm, "buildAppApiConfig")
        ReflectionHelpers.callInstanceMethod<Int>(
            vm,
            "addTarget",
            ReflectionHelpers.ClassParameter.from(
                TargetReport::class.java,
                TargetReport("app-api.pixiv.net", "", TargetStatus.HIGH_LATENCY),
            ),
        )
        vm.overall.value = OverallStatus.HIGH_LATENCY
    }

    @After
    fun tearDown() {
        store.clear()
        clients.forEach {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        Shaft.sSettings = oldSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
    }

    @Test
    fun `missing node fields cannot expose IPv4 in the fallback log`() {
        probe(responseClient("fl=123\nh=app-api.pixiv.net\nip=203.0.113.45\ncolo=\nloc="))

        val log = vm.rawLog.value.orEmpty()
        assertFalse(log, log.contains("203.0.113.45"))
        assertTrue(log, log.contains("ip=203.0.113.*"))
        assertInformational(StepStatus.INFO)
    }

    @Test
    fun `missing node fields cannot expose IPv6 in the fallback log`() {
        probe(responseClient("fl=123\r\nip=2001:db8::f03c:91ff:fe1e:1234\r\ncolo="))

        val log = vm.rawLog.value.orEmpty()
        assertFalse(log, log.contains("2001:db8::f03c:91ff:fe1e:1234"))
        assertTrue(log, log.contains("ip=2001:db8::*"))
        assertInformational(StepStatus.INFO)
    }

    @Test
    fun `cleared ViewModel does not start a new trace request`() {
        var requests = 0
        val client = responseClient("colo=NRT\nloc=JP") { requests++ }
        store.clear()

        val failure = runCatching { probe(client) }.exceptionOrNull()

        assertEquals(0, requests)
        assertTrue(failure.toString(), failure is CancellationException)
        assertTrue(vm.targets.value!!.single().steps.isEmpty())
    }

    @Test
    fun `successful trace preserves node fields and masks the complete log`() {
        probe(responseClient("fl=123\nip=203.0.113.45\ncolo=NRT\nloc=JP\nuag=a:b:c:d=e"))

        assertInformational(StepStatus.OK)
        assertEquals("JP → NRT", vm.targets.value!!.single().steps.single().detail)
        val log = vm.rawLog.value.orEmpty()
        assertTrue(log, log.contains("ip=203.0.113.*\ncolo=NRT\nloc=JP\nuag=a:b:c:d=e"))
        assertFalse(log, log.contains("203.0.113.45"))
    }

    @Test
    fun `HTTP failure with node fields remains informational`() {
        probe(responseClient("colo=NRT\nloc=JP", code = 503))

        assertInformational(StepStatus.INFO)
        assertTrue(vm.rawLog.value.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun `transport failure remains informational`() {
        probe(responseClient("") { throw IOException("trace unavailable") })

        assertInformational(StepStatus.INFO)
        assertTrue(vm.rawLog.value.orEmpty().contains("IOException: trace unavailable"))
    }

    @Test
    fun `unexpected body log is bounded`() {
        val body = "<html>" + "x".repeat(1000)
        probe(responseClient(body))

        val snippet = vm.rawLog.value.orEmpty().lineSequence()
            .first { it.startsWith("CDN trace 正文非 trace 格式: ") }
            .substringAfter(": ")
        assertEquals(body.take(200), snippet)
        assertInformational(StepStatus.INFO)
    }

    private fun responseClient(body: String, code: Int = 200, onRequest: () -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            onRequest()
            assertEquals("https://app-api.pixiv.net/cdn-cgi/trace", chain.request().url.toString())
            assertEquals(TimeUnit.SECONDS.toNanos(5), chain.call().timeout().timeoutNanos())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_2)
                .code(code)
                .message("fixture")
                .body(body.toResponseBody())
                .build()
        }.build().also { clients += it }

    private fun probe(client: OkHttpClient) {
        val method = NetworkTestViewModel::class.java.getDeclaredMethod(
            "probeCdnTrace", Int::class.javaPrimitiveType, cfg.javaClass, OkHttpClient::class.java,
        ).apply { isAccessible = true }
        try {
            method.invoke(vm, 0, cfg, client)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun assertInformational(stepStatus: StepStatus) {
        val target = vm.targets.value!!.single()
        assertEquals(stepStatus, target.steps.single().status)
        assertEquals(TargetStatus.HIGH_LATENCY, target.status)
        assertEquals(OverallStatus.HIGH_LATENCY, vm.overall.value)
    }
}
