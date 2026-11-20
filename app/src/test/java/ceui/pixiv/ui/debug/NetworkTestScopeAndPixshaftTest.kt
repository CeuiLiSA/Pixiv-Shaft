package ceui.pixiv.ui.debug

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelStore
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkTestScopeAndPixshaftTest {

    @get:Rule val executor = InstantTaskExecutorRule()

    private val store = ViewModelStore()
    private lateinit var vm: NetworkTestViewModel
    private var oldContext: Context? = null
    private var oldSettings: Settings? = null
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        oldContext = Shaft.getContext()
        oldSettings = Shaft.sSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        Shaft.sSettings = Settings()

        vm = NetworkTestViewModel()
        store.put("network", vm)
    }

    @After
    fun tearDown() {
        store.clear()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
        Shaft.sSettings = oldSettings
        server.shutdown()
    }

    @Test
    fun testModuleSelection_defaultsToAllAndUpdatesWithoutPersistence() {
        // 默认包含全部 4 个模块
        val defaults = vm.selectedModules.value
        assertNotNull(defaults)
        assertEquals(4, defaults?.size)
        assertTrue(vm.isModuleSelected(NetworkTestModule.APP_API))
        assertTrue(vm.isModuleSelected(NetworkTestModule.WEB_API))
        assertTrue(vm.isModuleSelected(NetworkTestModule.IMAGE))
        assertTrue(vm.isModuleSelected(NetworkTestModule.PIXSHAFT))

        // 仅选择 PIXSHAFT 和 APP_API
        val custom = setOf(NetworkTestModule.PIXSHAFT, NetworkTestModule.APP_API)
        vm.setSelectedModules(custom)

        assertEquals(custom, vm.selectedModules.value)
        assertTrue(vm.isModuleSelected(NetworkTestModule.PIXSHAFT))
        assertTrue(vm.isModuleSelected(NetworkTestModule.APP_API))
        assertFalse(vm.isModuleSelected(NetworkTestModule.WEB_API))
        assertFalse(vm.isModuleSelected(NetworkTestModule.IMAGE))
    }

    @Test
    fun testProbePixshaftHealth_successWhenOkTrue() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"service":"pixshaft-api","db":true}"""),
        )

        val targetReport = TargetReport("pixshaft.com", "", TargetStatus.RUNNING)
        val idx = ReflectionHelpers.callInstanceMethod<Int>(
            vm,
            "addTarget",
            ReflectionHelpers.ClassParameter.from(TargetReport::class.java, targetReport),
        )

        val targetConfigClass = Class.forName("ceui.pixiv.ui.debug.NetworkTestViewModel\$TargetConfig")
        val targetKindClass = Class.forName("ceui.pixiv.ui.debug.NetworkTestViewModel\$TargetKind")
        val pixshaftKind = java.lang.Enum.valueOf(targetKindClass.asSubclass(Enum::class.java), "PIXSHAFT")

        val ctor = targetConfigClass.declaredConstructors.first { it.parameterTypes.size == 7 }.apply { isAccessible = true }
        val cfg = ctor.newInstance(
            "${server.hostName}:${server.port}",
            "Shaft 云服务",
            null,
            pixshaftKind,
            true,
            null,
            null,
        )

        val alive = ReflectionHelpers.callInstanceMethod<Boolean>(
            vm,
            "probePixshaftHealth",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, idx),
            ReflectionHelpers.ClassParameter.from(targetConfigClass, cfg),
            ReflectionHelpers.ClassParameter.from(java.net.Inet4Address::class.java, null),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )

        assertTrue("源站返回 ok: true 应判定存活", alive)

        val request = server.takeRequest()
        assertEquals("/health", request.path)
        assertEquals("GET", request.method)

        val reports = vm.targets.value
        assertNotNull(reports)
        val step = reports!![idx].steps.last()
        assertEquals(StepStatus.OK, step.status)
        assertTrue(step.detail?.contains("HTTP 200") == true)
        assertTrue(step.detail?.contains("源站存活") == true || step.detail?.contains("Origin alive") == true)
    }

    @Test
    fun testProbePixshaftHealth_failedWhenOrigin502() {
        // Cloudflare 502 Bad Gateway 场景（源站挂了）
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"),
        )

        val targetReport = TargetReport("pixshaft.com", "", TargetStatus.RUNNING)
        val idx = ReflectionHelpers.callInstanceMethod<Int>(
            vm,
            "addTarget",
            ReflectionHelpers.ClassParameter.from(TargetReport::class.java, targetReport),
        )

        val targetConfigClass = Class.forName("ceui.pixiv.ui.debug.NetworkTestViewModel\$TargetConfig")
        val targetKindClass = Class.forName("ceui.pixiv.ui.debug.NetworkTestViewModel\$TargetKind")
        val pixshaftKind = java.lang.Enum.valueOf(targetKindClass.asSubclass(Enum::class.java), "PIXSHAFT")

        val ctor = targetConfigClass.declaredConstructors.first { it.parameterTypes.size == 7 }.apply { isAccessible = true }
        val cfg = ctor.newInstance(
            "${server.hostName}:${server.port}",
            "Shaft 云服务",
            null,
            pixshaftKind,
            true,
            null,
            null,
        )

        val alive = ReflectionHelpers.callInstanceMethod<Boolean>(
            vm,
            "probePixshaftHealth",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, idx),
            ReflectionHelpers.ClassParameter.from(targetConfigClass, cfg),
            ReflectionHelpers.ClassParameter.from(java.net.Inet4Address::class.java, null),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )

        assertFalse("源站 502 必须判定未存活", alive)

        val reports = vm.targets.value
        assertNotNull(reports)
        val step = reports!![idx].steps.last()
        assertEquals(StepStatus.FAIL, step.status)
        assertTrue(step.detail?.contains("HTTP 502") == true)
    }

    @Test
    fun testScopeSheet_rendersAndPicks() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var pickedResult: Set<NetworkTestModule>? = null

        NetworkTestScopeSheet.show(
            activity,
            setOf(NetworkTestModule.APP_API, NetworkTestModule.PIXSHAFT),
        ) { picked ->
            pickedResult = picked
        }

        // Sheet 正常初始化无异常
        assertNull("未点确认前回调不应执行", pickedResult)
    }
}
