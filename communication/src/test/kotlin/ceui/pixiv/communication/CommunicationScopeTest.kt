package ceui.pixiv.communication

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CommunicationScopeTest {
    @Test fun `close seals all topics before an observer can reenter siblings`() {
        lateinit var sibling: EventPublisher<Int>
        val results = mutableListOf<PublishResult>()
        val scope = CommunicationScope(observer = {
            if (it is CommunicationDiagnostic.Closed) results.add(sibling.tryPublish(1))
        })
        scope.eventTopic<Int>(EventTopicConfig("first"))
        sibling = scope.eventTopic<Int>(EventTopicConfig("second")).publisher
        scope.close()
        assertEquals(listOf(PublishResult.Closed, PublishResult.Closed), results)
    }

    @Test fun `scope has bounded topic capacity and validates config`() {
        CommunicationScope(maxTopics = 1).use { scope ->
            scope.eventTopic<Int>(EventTopicConfig("one"))
            assertThrows(IllegalStateException::class.java) { scope.stateTopic<Int, Int>(StateTopicConfig("two")) }
        }
        assertThrows(IllegalArgumentException::class.java) { EventTopicConfig("", 1) }
        assertThrows(IllegalArgumentException::class.java) { EventTopicConfig("zero", 0) }
        assertThrows(IllegalArgumentException::class.java) { StateTopicConfig("zero", maxKeys = 0) }
        assertThrows(IllegalArgumentException::class.java) { StateRetention.Cache(-1) }
    }

    @Test fun `inline consumers never run under publisher locks`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            CommunicationScope().use { scope ->
                val topic = scope.eventTopic<Int>(EventTopicConfig("unconfined"))
                val seen = mutableListOf<Int>()
                val collector = launch(Dispatchers.Unconfined) {
                    topic.source.events.collect {
                        seen.add(it)
                        if (it == 1) {
                            // If notification resumes us under the topic monitor, this other
                            // thread cannot publish and the timeout deterministically fails.
                            assertEquals(PublishResult.Accepted,
                                executor.submit<PublishResult> { topic.publisher.tryPublish(2) }.get(2, TimeUnit.SECONDS))
                        }
                    }
                }
                topic.publisher.tryPublish(1)
                scope.close()
                collector.join()
                assertEquals(listOf(1, 2), seen)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `close races publishers and state writers without stranding jobs`() = runTest {
        withContext(Dispatchers.Default) {
            repeat(100) {
                val scope = CommunicationScope()
                val events = scope.eventTopic<Int>(EventTopicConfig("race", 1, overflow = OverflowPolicy.Suspend))
                val states = scope.stateTopic<Unit, Int>(StateTopicConfig("state"))
                val reader = launch(start = CoroutineStart.UNDISPATCHED) { events.source.events.collect { yield() } }
                val stateReader = launch(start = CoroutineStart.UNDISPATCHED) { states.source.observe(Unit).collect { yield() } }
                listOf(
                    async { repeat(50) { events.publisher.publish(it) } },
                    async { repeat(50) { states.writer.set(Unit, it) } },
                    async { yield(); scope.close() },
                ).awaitAll()
                reader.join()
                stateReader.join()
                assertEquals(PublishResult.Closed, events.publisher.tryPublish(-1))
                assertEquals(StateWriteResult.CLOSED, states.writer.set(Unit, -1))
            }
        }
    }
}
