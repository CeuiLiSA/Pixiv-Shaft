package ceui.pixiv.communication.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.testing.TestLifecycleOwner
import ceui.pixiv.communication.CommunicationScope
import ceui.pixiv.communication.EventTopicConfig
import ceui.pixiv.communication.StateEntry
import ceui.pixiv.communication.StateTopicConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleSubscriptionsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `synchronous emissions stop when a handler destroys the view`() = runTest(dispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val seen = mutableListOf<Int>()
        val job = flowOf(1, 2).collectIn(owner, onError = { throw AssertionError(it) }) {
            seen.add(it)
            if (it == 1) {
                // Synchronous navigation/view teardown, without a suspending dispatch boundary.
                (owner.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1), seen)
    }

    @Test fun `stopping during the last synchronous value still allows the next lifecycle window`() = runTest(dispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        var seen = 0
        val job = flowOf(1).collectIn(owner, onError = { throw AssertionError(it) }) {
            seen++
            if (seen == 1) {
                (owner.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        assertFalse(job.isCompleted)
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(2, seen)
        assertTrue(job.isCompleted)
        owner.setCurrentState(Lifecycle.State.DESTROYED)
    }

    @Test fun `events follow view lifetime with no replay and no duplicate subscriptions`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("ui"))
            val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
            val seen = mutableListOf<Int>()
            val job = topic.source.collectIn(owner, onError = { throw AssertionError(it) }) { seen.add(it) }
            runCurrent()
            topic.publisher.publish(0)
            owner.setCurrentState(Lifecycle.State.STARTED)
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            owner.setCurrentState(Lifecycle.State.CREATED)
            runCurrent()
            topic.publisher.publish(2)
            owner.setCurrentState(Lifecycle.State.STARTED)
            runCurrent()
            topic.publisher.publish(3)
            runCurrent()
            assertEquals(listOf(1, 3), seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
            runCurrent()
            assertTrue(job.isCancelled)
            topic.publisher.publish(4)
            runCurrent()
            assertEquals(listOf(1, 3), seen)
        }
    }

    @Test fun `new view receives current keyed state after old view is destroyed`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, String>(StateTopicConfig("download-state"))
            val oldView = TestLifecycleOwner(Lifecycle.State.STARTED)
            val oldSeen = mutableListOf<StateEntry<String>>()
            topic.source.observe(42).collectIn(oldView, onError = { throw AssertionError(it) }) { oldSeen.add(it) }
            runCurrent()
            oldView.setCurrentState(Lifecycle.State.DESTROYED)
            runCurrent()
            topic.writer.set(42, "available")
            val newView = TestLifecycleOwner(Lifecycle.State.STARTED)
            val newSeen = mutableListOf<StateEntry<String>>()
            topic.source.observe(42).collectIn(newView, onError = { throw AssertionError(it) }) { newSeen.add(it) }
            runCurrent()
            assertEquals(listOf(StateEntry.Unknown), oldSeen)
            assertEquals(listOf(StateEntry.Value("available")), newSeen)
            newView.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `resumed policy ignores started pager neighbours`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("pager"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            val seen = mutableListOf<Int>()
            topic.source.collectIn(owner, minState = Lifecycle.State.RESUMED, onError = { throw AssertionError(it) }) { seen.add(it) }
            runCurrent()
            topic.publisher.publish(1)
            owner.setCurrentState(Lifecycle.State.RESUMED)
            runCurrent()
            topic.publisher.publish(2)
            runCurrent()
            assertEquals(listOf(2), seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `failed consumer stops permanently without affecting sibling subscriptions`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("failure"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            var failures = 0
            val failed = topic.source.collectIn(owner, onError = { failures++ }) { error("bad handler") }
            val seen = mutableListOf<Int>()
            topic.source.collectIn(owner, onError = { throw AssertionError(it) }) { seen.add(it) }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            assertTrue(failed.isCancelled)
            owner.setCurrentState(Lifecycle.State.CREATED)
            runCurrent()
            owner.setCurrentState(Lifecycle.State.STARTED)
            runCurrent()
            topic.publisher.publish(2)
            runCurrent()
            assertEquals(1, failures)
            assertEquals(listOf(1, 2), seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `continue policy reports bad message and handles next message`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("continue"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            var failures = 0
            val seen = mutableListOf<Int>()
            topic.source.collectIn(owner, errorPolicy = SubscriberErrorPolicy.ContinueAndReport, onError = { failures++ }) {
                if (it == 1) error("bad message")
                seen.add(it)
            }
            runCurrent()
            topic.publisher.publish(1)
            topic.publisher.publish(2)
            runCurrent()
            assertEquals(1, failures)
            assertEquals(listOf(2), seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `lifecycle cancellation is not reported and cancels suspended handler`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("cancel"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            var cancelled = false
            var failures = 0
            topic.source.collectIn(owner, onError = { failures++ }) {
                try { awaitCancellation() }
                finally { cancelled = true }
            }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            owner.setCurrentState(Lifecycle.State.DESTROYED)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, failures)
        }
    }

    @Test fun `closed source ends lifecycle subscription without a later restart`() = runTest(dispatcher) {
        val scope = CommunicationScope()
        val topic = scope.eventTopic<Int>(EventTopicConfig("closed"))
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
        val job = topic.source.collectIn(owner, onError = { throw AssertionError(it) }) {}
        runCurrent()
        scope.close()
        runCurrent()
        assertTrue(job.isCompleted)
        owner.setCurrentState(Lifecycle.State.CREATED)
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        assertTrue(job.isCompleted)
        owner.setCurrentState(Lifecycle.State.DESTROYED)
    }

    @Test fun `upstream failure is reported once even with continue policy`() = runTest(dispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
        var failures = 0
        val source = flow<Int> { throw IllegalStateException("storage unavailable") }
        val job = source.collectIn(owner, errorPolicy = SubscriberErrorPolicy.ContinueAndReport, onError = { failures++ }) {}
        runCurrent()
        assertTrue(job.isCompleted)
        owner.setCurrentState(Lifecycle.State.CREATED)
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(1, failures)
        owner.setCurrentState(Lifecycle.State.DESTROYED)
    }

    @Test fun `explicit cancellation detaches only the selected subscription`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("explicit"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            val seen = mutableListOf<Int>()
            val cancelled = topic.source.collectIn(owner, onError = { throw AssertionError(it) }) { fail("cancelled handler ran") }
            topic.source.collectIn(owner, onError = { throw AssertionError(it) }) { seen.add(it) }
            runCurrent()
            cancelled.cancel(CancellationException("caller cancelled"))
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            assertEquals(listOf(1), seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `handler cancellation is not retried in a later lifecycle window`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("handler-cancel"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            var attempts = 0
            val job = topic.source.collectIn(owner, onError = { throw AssertionError(it) }) {
                attempts++
                throw CancellationException("handler stopped")
            }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            assertTrue(job.isCancelled)
            owner.setCurrentState(Lifecycle.State.CREATED)
            owner.setCurrentState(Lifecycle.State.STARTED)
            runCurrent()
            topic.publisher.publish(2)
            runCurrent()
            assertEquals(1, attempts)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun `broken error reporter does not cancel healthy subscriber`() = runTest(dispatcher) {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("reporter"))
            val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
            topic.source.collectIn(owner, onError = { error("reporter failed") }) { error("handler failed") }
            var seen = 0
            topic.source.collectIn(owner, onError = { throw AssertionError(it) }) { seen++ }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            topic.publisher.publish(2)
            runCurrent()
            assertEquals(2, seen)
            owner.setCurrentState(Lifecycle.State.DESTROYED)
        }
    }
}
