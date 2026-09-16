package ceui.pixiv.plaza.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.plaza.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlazaModerationModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var uid = 42L
    private var reports = 0
    private var safetyChanges = 0
    private val safetyOwners = mutableListOf<Long>()
    private var blockResult = CompletableDeferred(DeletePost(true))
    private var result = CompletableDeferred(PlazaReportReceipt(123, "pending", false))
    private val unused = Proxy.newProxyInstance(PlazaApi::class.java.classLoader, arrayOf(PlazaApi::class.java)) { _, _, _ -> error("Unexpected API call") } as PlazaApi
    private val api = object : PlazaApi by unused {
        override suspend fun report(id: Long, body: PlazaReportRequest): PlazaReportReceipt {
            reports++
            return result.await()
        }
        override suspend fun block(uid: Long) = blockResult.await()
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(mode: String = "post", saved: SavedStateHandle = SavedStateHandle(mapOf("mode" to mode, "postId" to 1L, "targetUid" to 99L))) =
        PlazaModerationModel(saved, api, { uid }, { safetyChanges++; safetyOwners += it }).also { store.put("model", it) }

    @Test fun `rapid taps and repeated submits cannot duplicate a pending or successful report`() = runTest(dispatcher) {
        result = CompletableDeferred()
        val vm = model().apply { reason = "spam" }
        vm.submit(); vm.submit(); runCurrent()
        assertEquals(1, reports); assertTrue(vm.state.value.busy)
        result.complete(PlazaReportReceipt(123,"pending",false)); runCurrent()
        vm.submit(); runCurrent()
        assertEquals(1, reports); assertEquals(123L,vm.state.value.receiptId); assertTrue(vm.state.value.done)
    }
    @Test fun `failed delivery preserves draft and allows retry with the same natural report identity`() = runTest(dispatcher) {
        result = CompletableDeferred<PlazaReportReceipt>().also { it.completeExceptionally(IOException("offline")) }
        val saved = SavedStateHandle(mapOf("mode" to "post", "postId" to 1L, "targetUid" to 99L))
        val vm = model(saved=saved).apply { reason="other"; details="full context" }
        vm.submit(); runCurrent()
        assertFalse(vm.state.value.done); assertNotNull(vm.state.value.error)
        assertEquals("full context",saved.get<String>("details"))
        result=CompletableDeferred(PlazaReportReceipt(123,"removed",true))
        vm.submit(); runCurrent()
        assertEquals(2,reports); assertEquals("removed",vm.state.value.receiptStatus)
    }
    @Test fun `account switches cannot submit or publish a previous accounts result`() = runTest(dispatcher) {
        result=CompletableDeferred()
        val vm=model().apply { reason="spam" }
        vm.submit(); runCurrent(); uid=99
        result.complete(PlazaReportReceipt(123,"pending",false)); runCurrent()
        assertFalse(vm.state.value.done); assertNull(vm.state.value.receiptId)
        vm.submit(); runCurrent(); assertEquals(1,reports)
    }
    @Test fun `accepted block clears its original account cache after an account switch`() = runTest(dispatcher) {
        blockResult = CompletableDeferred()
        val vm = model("block")
        vm.submit(); runCurrent()
        uid = 99L
        blockResult.complete(DeletePost(true)); runCurrent()
        assertEquals(listOf(42L), safetyOwners)
        assertFalse(vm.state.value.done)
    }

    @Test fun `lost block response still invalidates potentially obsolete cached content`() = runTest(dispatcher) {
        blockResult = CompletableDeferred<DeletePost>().also { it.completeExceptionally(IOException("response lost after commit")) }
        val vm = model("block")
        vm.submit(); runCurrent()
        assertEquals(listOf(42L), safetyOwners)
        assertFalse(vm.state.value.done)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `other reason requires details and successful blocking invalidates cached content`() = runTest(dispatcher) {
        val vm=model().apply { reason="other" }
        vm.submit(); runCurrent(); assertEquals(0,reports); assertNotNull(vm.state.value.error)
        val block=model("block"); block.submit(); runCurrent()
        assertTrue(block.state.value.done); assertEquals(1,safetyChanges)
    }
}
