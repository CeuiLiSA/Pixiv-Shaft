package ceui.pixiv.communication

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventTopicTest {
    @Test fun `broadcast reaches both collectors and topic identity is not its name`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("same"))
            val other = scope.eventTopic<String>(EventTopicConfig("same"))
            val first = mutableListOf<Int>()
            val second = mutableListOf<Int>()
            val unrelated = mutableListOf<String>()
            backgroundScope.launch { topic.source.events.collect { first.add(it) } }
            backgroundScope.launch { topic.source.events.collect { second.add(it) } }
            backgroundScope.launch { other.source.events.collect { unrelated.add(it) } }
            runCurrent()
            assertEquals(PublishResult.Accepted, topic.publisher.publish(1))
            assertEquals(PublishResult.Accepted, topic.publisher.publish(2))
            runCurrent()
            assertEquals(listOf(1, 2), first)
            assertEquals(first, second)
            assertTrue(unrelated.isEmpty())
        }
    }

    @Test fun `no subscribers means no replay even with large capacity`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("ephemeral", bufferCapacity = 1_024))
            assertEquals(PublishResult.Accepted, topic.publisher.publish(1))
            val next = async { topic.source.events.first() }
            runCurrent()
            assertFalse(next.isCompleted)
            topic.publisher.publish(2)
            assertEquals(2, next.await())
        }
    }

    @Test fun `full broadcast rejects for everyone rather than delivering partially`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("bounded", bufferCapacity = 1))
            val gate = CompletableDeferred<Unit>()
            val slow = mutableListOf<Int>()
            val fast = mutableListOf<Int>()
            backgroundScope.launch {
                topic.source.events.collect { slow.add(it); gate.await() }
            }
            backgroundScope.launch { topic.source.events.collect { fast.add(it) } }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent() // Both dequeued 1; slow remains inside its handler.
            topic.publisher.publish(2)
            runCurrent() // Fast has consumed 2; slow's mailbox is full.
            assertEquals(PublishResult.RejectedFull, topic.publisher.tryPublish(3))
            runCurrent()
            assertEquals(listOf(1, 2), fast)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), slow)
        }
    }

    @Test fun `suspended publication resumes when slow collector frees capacity`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("wait", 1, overflow = OverflowPolicy.Suspend))
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<Int>()
            backgroundScope.launch { topic.source.events.collect { seen.add(it); gate.await() } }
            runCurrent()
            topic.publisher.publish(1)
            runCurrent()
            topic.publisher.publish(2)
            val waiting = async { topic.publisher.publish(3) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            gate.complete(Unit)
            assertEquals(PublishResult.Accepted, waiting.await())
            runCurrent()
            assertEquals(listOf(1, 2, 3), seen)
        }
    }

    @Test fun `cancelling a waiting publisher does not enqueue it later`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("cancel", 1, overflow = OverflowPolicy.Suspend))
            val seen = mutableListOf<Int>()
            backgroundScope.launch { topic.source.events.collect { seen.add(it) } }
            runCurrent()
            topic.publisher.publish(1)
            // UNDISPATCHED runs before the collector can free capacity.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { topic.publisher.publish(2) }
            assertFalse(waiting.isCompleted)
            waiting.cancelAndJoin()
            runCurrent()
            assertEquals(listOf(1), seen)
        }
    }

    @Test fun `last subscriber leaving wakes blocked publishers`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("unsubscribe", 1, overflow = OverflowPolicy.Suspend))
            val job = backgroundScope.launch { topic.source.events.collect {} }
            runCurrent()
            topic.publisher.publish(1)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { topic.publisher.publish(2) }
            job.cancelAndJoin()
            assertEquals(PublishResult.Accepted, waiting.await())
        }
    }

    @Test fun `close wakes publishers and idle collectors and discards queues`() = runTest {
        val scope = CommunicationScope()
        val topic = scope.eventTopic<Int>(EventTopicConfig("close", 1, overflow = OverflowPolicy.Suspend))
        val seen = mutableListOf<Int>()
        val collector = launch { topic.source.events.collect { seen.add(it) } }
        runCurrent()
        topic.publisher.publish(1)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { topic.publisher.publish(2) }
        scope.close()
        scope.close()
        assertEquals(PublishResult.Closed, waiting.await())
        collector.join()
        assertTrue(seen.isEmpty())
        assertEquals(PublishResult.Closed, topic.publisher.tryPublish(3))
        topic.source.events.collect { fail("closed source emitted") }
        assertThrows(ScopeClosedException::class.java) { scope.eventTopic<Int>(EventTopicConfig("new")) }
    }

    @Test fun `consumer failure and observer failure do not break publishing or siblings`() = runTest {
        CommunicationScope(observer = { throw IllegalStateException("diagnostics") }).use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("isolation"))
            var failed = false
            backgroundScope.launch {
                try { topic.source.events.collect { error("consumer") } }
                catch (_: IllegalStateException) { failed = true }
            }
            val seen = mutableListOf<Int>()
            backgroundScope.launch { topic.source.events.collect { seen.add(it) } }
            runCurrent()
            assertEquals(PublishResult.Accepted, topic.publisher.publish(1))
            runCurrent()
            assertTrue(failed)
            assertEquals(PublishResult.Accepted, topic.publisher.publish(2))
            runCurrent()
            assertEquals(listOf(1, 2), seen)
        }
    }

    @Test fun `subscriber limit is enforced and slot is released on cancellation`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("limit", maxSubscribers = 1))
            val job = backgroundScope.launch { topic.source.events.collect {} }
            runCurrent()
            try {
                topic.source.events.collect {}
                fail("expected capacity failure")
            } catch (_: SubscriptionCapacityException) { }
            job.cancelAndJoin()
            val replacement = async { topic.source.events.first() }
            runCurrent()
            topic.publisher.publish(9)
            assertEquals(9, replacement.await())
        }
    }

    @Test fun `concurrent publishers have identical order at each subscriber`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.eventTopic<Int>(EventTopicConfig("concurrency", 4, overflow = OverflowPolicy.Suspend))
            val a = Collections.synchronizedList(mutableListOf<Int>())
            val b = Collections.synchronizedList(mutableListOf<Int>())
            val doneA = CompletableDeferred<Unit>()
            val doneB = CompletableDeferred<Unit>()
            val count = 2_000
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                topic.source.events.collect { a.add(it); if (a.size == count) doneA.complete(Unit) }
            }
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                topic.source.events.collect { b.add(it); if (b.size == count) doneB.complete(Unit) }
            }
            withContext(Dispatchers.Default) {
                (0 until 8).map { worker ->
                    async {
                        repeat(count / 8) { i ->
                            assertEquals(PublishResult.Accepted, topic.publisher.publish(worker * 1_000 + i))
                        }
                    }
                }.awaitAll()
            }
            doneA.await()
            doneB.await()
            assertEquals(a, b)
            assertEquals(count, a.toSet().size)
        }
    }

    @Test fun `different topics do not share backpressure`() = runTest {
        CommunicationScope().use { scope ->
            val blocked = scope.eventTopic<Int>(EventTopicConfig("blocked", 1))
            val free = scope.eventTopic<Int>(EventTopicConfig("free", 1))
            backgroundScope.launch { blocked.source.events.collect {} }
            val next = async { free.source.events.first() }
            runCurrent()
            blocked.publisher.publish(1)
            assertEquals(PublishResult.RejectedFull, blocked.publisher.tryPublish(2))
            assertEquals(PublishResult.Accepted, free.publisher.tryPublish(3))
            assertEquals(3, next.await())
        }
    }
}
