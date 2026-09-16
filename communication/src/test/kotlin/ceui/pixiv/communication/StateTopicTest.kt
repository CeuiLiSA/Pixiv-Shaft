package ceui.pixiv.communication

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
class StateTopicTest {
    @Test fun `late collectors see latest state for their key and equality is conflated`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<String, Int>(StateTopicConfig("state"))
            topic.writer.set("a", 1)
            topic.writer.set("b", 99)
            val seen = mutableListOf<StateEntry<Int>>()
            backgroundScope.launch { topic.source.observe("a").collect { seen.add(it) } }
            runCurrent()
            assertEquals(listOf(StateEntry.Value(1)), seen)
            assertEquals(StateWriteResult.UNCHANGED, topic.writer.set("a", 1))
            topic.writer.set("b", 100)
            runCurrent()
            assertEquals(1, seen.size)
            topic.writer.set("a", 2)
            topic.writer.set("a", 3)
            runCurrent()
            assertEquals(listOf(StateEntry.Value(1), StateEntry.Value(3)), seen)
        }
    }

    @Test fun `remove keeps existing observers attached to future writes`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, String>(StateTopicConfig("remove"))
            val seen = mutableListOf<StateEntry<String>>()
            backgroundScope.launch { topic.source.observe(1).collect { seen.add(it) } }
            runCurrent()
            topic.writer.set(1, "old")
            runCurrent()
            topic.writer.remove(1)
            runCurrent()
            topic.writer.set(1, "new")
            runCurrent()
            assertEquals(listOf(StateEntry.Unknown, StateEntry.Value("old"), StateEntry.Unknown, StateEntry.Value("new")), seen)
        }
    }

    @Test fun `retained state rejects new keys instead of evicting authority`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, String>(StateTopicConfig("retain", maxKeys = 1))
            topic.writer.set(1, "original")
            var transformed = false
            assertEquals(StateWriteResult.REJECTED_CAPACITY, topic.writer.update(2) { transformed = true; "bad" })
            assertFalse(transformed)
            assertEquals(StateEntry.Value("original"), topic.source.observe(1).first())
            try {
                topic.source.observe(2).first()
                fail("expected key capacity failure")
            } catch (_: SubscriptionCapacityException) { }
            topic.writer.remove(1)
            assertEquals(StateWriteResult.APPLIED, topic.writer.set(2, "replacement"))
        }
    }

    @Test fun `unobserved unknown entries release capacity`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("unknown", maxKeys = 1))
            repeat(100) { assertEquals(StateEntry.Unknown, topic.source.observe(it).first()) }
            assertEquals(StateWriteResult.APPLIED, topic.writer.set(101, 1))
        }
    }

    @Test fun `cache expires with monotonic time and last unsubscribe starts idle TTL`() = runTest {
        CommunicationScope(clock = { testScheduler.currentTime }).use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("cache", retention = StateRetention.Cache(100)))
            topic.writer.set(1, 7)
            val job = backgroundScope.launch { topic.source.observe(1).collect {} }
            runCurrent()
            testScheduler.advanceTimeBy(1_000)
            assertEquals(StateEntry.Value(7), topic.source.observe(1).first()) // Active keys don't expire.
            job.cancelAndJoin()
            testScheduler.advanceTimeBy(99)
            assertEquals(StateEntry.Value(7), topic.source.observe(1).first()) // Refreshes idle start.
            testScheduler.advanceTimeBy(100)
            assertEquals(StateEntry.Unknown, topic.source.observe(1).first())
        }
    }

    @Test fun `cache evicts LRU inactive keys but never active keys`() = runTest {
        CommunicationScope(clock = { testScheduler.currentTime }).use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("lru", maxKeys = 2, retention = StateRetention.Cache(10_000)))
            topic.writer.set(1, 10)
            testScheduler.advanceTimeBy(1)
            topic.writer.set(2, 20)
            val active = backgroundScope.launch { topic.source.observe(1).collect {} }
            runCurrent()
            topic.writer.set(3, 30) // Evicts 2, despite 1 having been created earlier.
            assertEquals(StateEntry.Value(10), topic.source.observe(1).first())
            assertEquals(StateEntry.Value(30), topic.source.observe(3).first())
            assertEquals(StateEntry.Unknown, topic.source.observe(2).first())
            active.cancelAndJoin()
        }
    }

    @Test fun `zero TTL releases value immediately after last collection`() = runTest {
        CommunicationScope(clock = { testScheduler.currentTime }).use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("zero", retention = StateRetention.Cache(0)))
            val seen = mutableListOf<StateEntry<Int>>()
            val job = backgroundScope.launch { topic.source.observe(1).collect { seen.add(it) } }
            runCurrent()
            topic.writer.set(1, 5)
            runCurrent()
            assertEquals(StateEntry.Value(5), seen.last())
            job.cancelAndJoin()
            assertEquals(StateEntry.Unknown, topic.source.observe(1).first())
        }
    }

    @Test fun `failed reducer neither evicts another key nor changes current state`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("atomic", maxKeys = 1, retention = StateRetention.Cache(Long.MAX_VALUE)))
            topic.writer.set(1, 10)
            assertThrows(IllegalArgumentException::class.java) { topic.writer.update(2) { throw IllegalArgumentException() } }
            assertEquals(StateEntry.Value(10), topic.source.observe(1).first())
            assertThrows(IllegalArgumentException::class.java) { topic.writer.update(1) { throw IllegalArgumentException() } }
            assertEquals(StateEntry.Value(10), topic.source.observe(1).first())
        }
    }

    @Test fun `reentrant mutations are rejected without corrupting state`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("pure"))
            topic.writer.set(1, 1)
            assertThrows(IllegalStateException::class.java) { topic.writer.update(1) { topic.writer.set(1, 2); 3 } }
            assertEquals(StateEntry.Value(1), topic.source.observe(1).first())
        }
    }

    @Test fun `concurrent atomic updates do not lose increments`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Unit, Int>(StateTopicConfig("counter"))
            withContext(Dispatchers.Default) {
                (0 until 8).map {
                    async { repeat(1_000) { topic.writer.update(Unit) { ((it as? StateEntry.Value)?.value ?: 0) + 1 } } }
                }.awaitAll()
            }
            assertEquals(StateEntry.Value(8_000), topic.source.observe(Unit).first())
        }
    }

    @Test fun `close completes collectors and old writer cannot affect a new scope`() = runTest {
        val scope = CommunicationScope()
        val topic = scope.stateTopic<Int, String>(StateTopicConfig("session"))
        val job = launch { topic.source.observe(1).collect {} }
        runCurrent()
        scope.close()
        job.join()
        assertEquals(StateWriteResult.CLOSED, topic.writer.set(1, "late"))
        assertEquals(StateWriteResult.CLOSED, topic.writer.remove(1))
        topic.source.observe(1).collect { fail("closed source emitted") }
        CommunicationScope().use { next ->
            val replacement = next.stateTopic<Int, String>(StateTopicConfig("session"))
            assertEquals(StateEntry.Unknown, replacement.source.observe(1).first())
        }
    }

    @Test fun `subscriber capacity counts repeated observations of the same key`() = runTest {
        CommunicationScope().use { scope ->
            val topic = scope.stateTopic<Int, Int>(StateTopicConfig("limit", maxSubscribers = 1))
            val job = backgroundScope.launch { topic.source.observe(1).collect {} }
            runCurrent()
            try { topic.source.observe(1).first(); fail("expected subscription limit") }
            catch (_: SubscriptionCapacityException) { }
            job.cancelAndJoin()
            assertEquals(StateEntry.Unknown, topic.source.observe(1).first())
        }
    }
}
