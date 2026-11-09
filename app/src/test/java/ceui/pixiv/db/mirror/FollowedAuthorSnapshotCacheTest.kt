package ceui.pixiv.db.mirror

import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedAuthorSnapshotCacheTest {
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }

    @Test
    fun `one-shot filtering after invalidation must use the new follows before refresh executes`() {
        val executor = QueuedExecutor()
        var followedIds = emptyList<Long>()
        val cache = FollowedAuthorSnapshotCache(executor) { followedIds }
        assertFalse(cache.contains(1L, 100L))

        // The second following shelf has just completed its first sync. A feed mapper runs
        // before Room's query executor gets to the queued refresh and permanently drops rows.
        followedIds = listOf(100L)
        cache.invalidate()
        val visibleAuthors = listOf(100L).filter { cache.contains(1L, it) }
        executor.runNext()

        assertEquals("publishing a fresh cache cannot restore a row already dropped by the mapper",
            listOf(100L), visibleAuthors)
    }

    @Test
    fun `one-shot filtering must revoke an unfollowed author's exemption before refresh executes`() {
        val executor = QueuedExecutor()
        var followedIds = listOf(100L)
        val cache = FollowedAuthorSnapshotCache(executor) { followedIds }
        assertTrue(cache.contains(1L, 100L))

        followedIds = emptyList()
        cache.invalidate()
        val visibleAuthors = listOf(100L).filter { cache.contains(1L, it) }
        executor.runNext()

        assertTrue(visibleAuthors.isEmpty())
    }

    @Test
    fun `table invalidations refresh in background without making readers reload`() {
        val executor = QueuedExecutor()
        var ids = listOf(100L)
        var reads = 0
        val cache = FollowedAuthorSnapshotCache(executor) { reads++; ids }
        assertTrue(cache.contains(1L, 100L))

        ids = listOf(200L)
        repeat(3) { cache.invalidate() }
        repeat(30) { assertTrue(cache.containsCached(1L, 100L)) }
        assertEquals("bind must not repeat the full ID query", 1, reads)
        assertEquals("bursts share one refresh", 1, executor.tasks.size)

        executor.runNext()
        assertFalse(cache.contains(1L, 100L))
        assertTrue(cache.contains(1L, 200L))
        assertEquals(2, reads)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun `invalidation during a load is not lost when its result is published`() {
        val executor = QueuedExecutor()
        var reads = 0
        lateinit var cache: FollowedAuthorSnapshotCache
        cache = FollowedAuthorSnapshotCache(executor) {
            reads++
            if (reads == 2) cache.invalidate()
            listOf(reads.toLong())
        }
        assertTrue(cache.contains(1L, 1L))
        cache.invalidate()
        executor.runNext()
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertTrue(cache.contains(1L, 3L))
        assertFalse(cache.contains(1L, 2L))
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun `switching account while a refresh is queued never reuses the previous IDs`() {
        val executor = QueuedExecutor()
        val cache = FollowedAuthorSnapshotCache(executor) { uid -> listOf(uid * 100L) }
        assertTrue(cache.contains(1L, 100L))
        cache.invalidate()
        assertFalse(cache.contains(2L, 100L))
        assertTrue(cache.contains(2L, 200L))
        executor.runNext()
        assertFalse(cache.contains(2L, 100L))
        assertTrue(cache.contains(1L, 100L))
        assertFalse(cache.contains(1L, 200L))
    }

    @Test
    fun `failed refresh preserves the snapshot and can retry without spinning`() {
        val executor = QueuedExecutor()
        var reads = 0
        val cache = FollowedAuthorSnapshotCache(executor) {
            reads++
            check(reads != 2) { "database temporarily unavailable" }
            listOf(reads.toLong())
        }
        assertTrue(cache.contains(1L, 1L))
        cache.invalidate()
        executor.runNext()
        assertTrue(executor.tasks.isEmpty())
        assertTrue(cache.containsCached(1L, 1L))
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertTrue(cache.contains(1L, 3L))
    }

    @Test
    fun `an in-flight refresh never holds up a warm reader`() {
        val executor = Executors.newSingleThreadExecutor()
        val reader = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val cache = FollowedAuthorSnapshotCache(executor) {
            val read = reads.incrementAndGet()
            if (read > 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            listOf(if (read == 1) 100L else 200L)
        }
        try {
            assertTrue(cache.contains(1L, 100L))
            cache.invalidate()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(reader.submit(Callable { cache.containsCached(1L, 100L) }).get(1, TimeUnit.SECONDS))
            val fresh = reader.submit(Callable { cache.contains(1L, 200L) })
            release.countDown()
            assertTrue(fresh.get(5, TimeUnit.SECONDS))
            assertEquals("filtering reuses the completed refresh", 2, reads.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            reader.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            reader.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `invalid user or account IDs never load a snapshot`() {
        val cache = FollowedAuthorSnapshotCache(QueuedExecutor()) { error("must not load") }
        assertFalse(cache.contains(0L, 100L))
        assertFalse(cache.contains(-1L, 100L))
        assertFalse(cache.contains(1L, 0L))
        assertFalse(cache.contains(1L, -1L))
    }
}
