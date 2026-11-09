package ceui.pixiv.ui.bulk

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import com.blankj.utilcode.util.Utils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.ManagerReactive
import ceui.lisa.utils.Settings
import ceui.pixiv.db.queue.DownloadQueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QueueDownloadManagerEventsTest {
    private var oldSettings: Settings? = null
    private var oldContext: Context? = null

    @Before fun setUp() {
        oldSettings = Shaft.sSettings
        oldContext = Shaft.getContext()
        Shaft.sSettings = Settings().apply { downloadLimitType = 0 }
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        Utils.init(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() {
        Shaft.sSettings = oldSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
    }

    @Test fun `pending count failure keeps polling without another external event`() = runTest {
        val queries = QueueQueries(countFailures = 1, pendingCount = 1)
        val manager = manager(backgroundScope, queries)
        manager.start()
        runCurrent()
        assertEquals(1, queries.countCalls)

        advanceTimeBy(800)
        runCurrent()

        assertEquals("A failed count must not put pending work into indefinite sleep", 2, queries.countCalls)
        assertTrue("Keep the user's release while queue state is unknown", field(manager, "userForced"))
    }

    @Test fun `failed idle recovery retries before releasing the batch and then sleeps`() = runTest {
        val queries = QueueQueries(recoveryFailures = 1)
        val manager = manager(backgroundScope, queries)
        ReflectionHelpers.setField(manager, "idleRecoveryPending", true)
        manager.start()
        runCurrent()
        assertEquals(1, queries.recoveryCalls)
        assertTrue("Recovery is still part of the manually released batch", field(manager, "userForced"))

        advanceTimeBy(800)
        runCurrent()

        assertEquals(2, queries.recoveryCalls)
        assertFalse(field(manager, "idleRecoveryPending"))
        assertFalse(field(manager, "userForced"))
        val countAfterRecovery = queries.countCalls
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("Successful recovery must return to event-only idle", countAfterRecovery, queries.countCalls)
        assertEquals(2, queries.recoveryCalls)
    }

    @Test fun `empty queue ignores ordinary download events and progress`() = runTest {
        val queries = QueueQueries()
        val manager = manager(backgroundScope, queries)
        manager.start()
        runCurrent()

        repeat(100) {
            ManagerReactive.invalidateProgress()
            ManagerReactive.invalidate()
            runCurrent()
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, queries.countCalls)
        assertEquals(0, queries.recoveryCalls)
    }

    @Test fun `running ugoira defers recovery until its worker wakes the consumer`() = runTest {
        val queries = QueueQueries()
        val manager = manager(backgroundScope, queries)
        val workers = ReflectionHelpers.getField<MutableSet<Long>>(manager, "ugoiraInFlightRowIds")
        workers.add(7L)
        ReflectionHelpers.setField(manager, "idleRecoveryPending", true)
        manager.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, queries.recoveryCalls)
        assertEquals(1, queries.countCalls)
        assertTrue(field(manager, "userForced"))

        workers.remove(7L)
        ReflectionHelpers.getField<Channel<Unit>>(manager, "tickle").trySend(Unit)
        runCurrent()

        assertEquals(1, queries.recoveryCalls)
        assertFalse(field(manager, "idleRecoveryPending"))
    }

    @Test fun `cold start recovery failure is retried by the main loop`() = runTest {
        val queries = QueueQueries(recoveryFailures = 1)
        val manager = manager(backgroundScope, queries)
        ReflectionHelpers.setField(manager, "coldStartDone", false)
        manager.start()
        runCurrent()

        assertEquals("A failed cold-start recovery must remain eligible for retry", 2, queries.recoveryCalls)
        assertFalse(field(manager, "idleRecoveryPending"))
    }

    @Test fun `progress still reaches UI while only task changes reach the queue`() = runTest {
        var uiEvents = 0
        var workEvents = 0
        backgroundScope.launch { ManagerReactive.contentFlow.collect { uiEvents++ } }
        backgroundScope.launch { ManagerReactive.queueWorkFlow.collect { workEvents++ } }
        runCurrent()
        assertEquals(1, uiEvents)
        assertEquals(1, workEvents)

        ManagerReactive.invalidateProgress()
        runCurrent()
        assertEquals(2, uiEvents)
        assertEquals(1, workEvents)

        ManagerReactive.invalidate()
        runCurrent()
        assertEquals(3, uiEvents)
        assertEquals(2, workEvents)
    }

    private fun manager(scope: CoroutineScope, queries: QueueQueries): QueueDownloadManager =
        QueueDownloadManager(RuntimeEnvironment.getApplication()).also {
            // Exercise the real loop with virtual time; cold-start UI and DB opening are unrelated.
            ReflectionHelpers.setField(it, "scope", scope)
            ReflectionHelpers.setField(it, "coldStartDone", true)
            // Model an already released batch without resume() preloading an extra tick.
            ReflectionHelpers.setField(it, "userForced", true)
            ReflectionHelpers.setField(it, "dao\$delegate", lazyOf(queries.dao))
        }

    private fun field(manager: QueueDownloadManager, name: String): Boolean =
        ReflectionHelpers.getField(manager, name)

    private class QueueQueries(
        var countFailures: Int = 0,
        var recoveryFailures: Int = 0,
        val pendingCount: Int = 0,
    ) {
        var countCalls = 0
        var recoveryCalls = 0
        val dao = Proxy.newProxyInstance(
            DownloadQueueDao::class.java.classLoader,
            arrayOf(DownloadQueueDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "nextByStatus", "nextByStatusExcluding" -> null
                "countByStatus" -> {
                    countCalls++
                    if (countFailures-- > 0) throw SQLiteException("temporary count failure")
                    pendingCount
                }
                "resurrectInProgress" -> {
                    recoveryCalls++
                    if (recoveryFailures-- > 0) throw SQLiteException("temporary recovery failure")
                    0
                }
                else -> error("Unexpected queue query: ${method.name}")
            }
        } as DownloadQueueDao
    }
}
