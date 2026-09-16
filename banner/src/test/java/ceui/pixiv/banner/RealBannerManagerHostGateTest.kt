package ceui.pixiv.banner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealBannerManagerHostGateTest {

    @Test
    fun `request enqueued without a started host stays pending and is not auto-dismissed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = RealBannerManager(binders = emptyMap(), parentContext = dispatcher)
        manager.start()

        manager.enqueue(request("mirror-ready"))

        assertEquals(BannerState.Idle, manager.state.value)
        assertEquals(1, manager.queueSize.value)

        advanceTimeBy(10_000)
        assertEquals(BannerState.Idle, manager.state.value)
        assertEquals(1, manager.queueSize.value)

        manager.onHostStarted()

        val presenting = manager.state.value as BannerState.Presenting
        assertEquals("mirror-ready", presenting.request.id)
        assertEquals(0, manager.queueSize.value)

        manager.onHostStopped()
        manager.shutdown()
    }

    @Test
    fun `preempt while no host holds both the new request and the displaced one`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = RealBannerManager(binders = emptyMap(), parentContext = dispatcher)
        manager.start()
        manager.onHostStarted()

        manager.enqueue(request("first", autoDismissMillis = null))
        assertEquals("first", (manager.state.value as BannerState.Presenting).request.id)

        manager.onHostStopped()
        manager.enqueue(
            request(
                id = "second",
                policy = BannerDisplayPolicy.Preempt,
                autoDismissMillis = null,
            ),
        )

        assertEquals(BannerState.Idle, manager.state.value)
        assertEquals(2, manager.queueSize.value)

        manager.onHostStarted()
        assertEquals("second", (manager.state.value as BannerState.Presenting).request.id)

        manager.dismiss("second", BannerDismissReason.UserTap)
        assertEquals("first", (manager.state.value as BannerState.Presenting).request.id)

        manager.dismiss("first", BannerDismissReason.UserTap)
        assertEquals(BannerState.Idle, manager.state.value)

        manager.shutdown()
    }

    @Test
    fun `hasStartedHost tracks host presence so enqueuers can suppress transient banners`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = RealBannerManager(binders = emptyMap(), parentContext = dispatcher)
        manager.start()

        assertEquals(false, manager.hasStartedHost.value)

        manager.onHostStarted()
        assertEquals(true, manager.hasStartedHost.value)

        // 两个宿主时走掉一个仍然有人能画（Activity A→B 切换的中间态）。
        manager.onHostStarted()
        manager.onHostStopped()
        assertEquals(true, manager.hasStartedHost.value)

        manager.onHostStopped()
        assertEquals(false, manager.hasStartedHost.value)

        manager.shutdown()
    }

    private fun request(
        id: String,
        policy: BannerDisplayPolicy = BannerDisplayPolicy.Enqueue,
        autoDismissMillis: Long? = 7000L,
    ): BannerRequest = BannerRequest.Text(
        id = id,
        title = id,
        policy = policy,
        autoDismissMillis = autoDismissMillis,
    )
}
