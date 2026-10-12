package ceui.pixiv.feeds.cache

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FeedFirstPageCache] 的存储无关单测：注入内存假 backend + 固定时钟 + 普通 Gson，
 * 不碰 Room / Android。锁定命中往返、未命中、过期、版本作废、坏数据兜底、账号隔离、清除。
 */
class FeedFirstPageCacheTest {

    private data class FakeResp(val a: Int, val b: String)

    private data class OtherResp(val x: Int)

    private class InMemoryBackend : FeedCacheBackend {
        val map = HashMap<String, FeedCacheRecord>()
        override suspend fun load(key: String): FeedCacheRecord? = map[key]
        override suspend fun save(key: String, record: FeedCacheRecord) {
            map[key] = record
        }
        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    private fun cache(
        backend: FeedCacheBackend,
        account: Long = 1L,
        now: () -> Long = { 1_000L },
        maxAgeMillis: Long = 10_000L,
    ) = FeedFirstPageCache(
        slot = "s",
        type = FakeResp::class.java,
        maxAgeMillis = maxAgeMillis,
        backend = backend,
        gson = Gson(),
        accountId = { account },
        now = now,
    )

    @Test
    fun `write then read round-trips payload and cursor`() = runBlocking {
        val cache = cache(InMemoryBackend())
        cache.write(FakeResp(7, "x"), "next-url")

        val got = cache.read()
        assertNotNull(got)
        assertEquals(FakeResp(7, "x"), got!!.payload)
        assertEquals("next-url", got.nextCursor)
    }

    @Test
    fun `miss returns null`() = runBlocking {
        assertNull(cache(InMemoryBackend()).read())
    }

    @Test
    fun `expired snapshot returns null`() = runBlocking {
        var clock = 1_000L
        val cache = cache(InMemoryBackend(), now = { clock }, maxAgeMillis = 10_000L)
        cache.write(FakeResp(1, "a"), null)

        clock = 1_000L + 10_001L // 超过 maxAge
        assertNull(cache.read())
    }

    @Test
    fun `schema version mismatch is treated as miss`() = runBlocking {
        val backend = InMemoryBackend()
        val cache = cache(backend)
        cache.write(FakeResp(1, "a"), null) // 落一条正常快照，拿到真实键（不耦合键格式）
        val key = backend.map.keys.single()
        backend.map[key] = backend.map.getValue(key)
            .copy(schemaVersion = FeedFirstPageCache.SCHEMA_VERSION + 1)

        assertNull(cache.read())
    }

    @Test
    fun `corrupt json returns null instead of throwing`() = runBlocking {
        val backend = InMemoryBackend()
        val cache = cache(backend)
        cache.write(FakeResp(1, "a"), null)
        val key = backend.map.keys.single()
        backend.map[key] = backend.map.getValue(key).copy(payloadJson = "{ not valid json")

        assertNull(cache.read())
    }

    @Test
    fun `different accounts do not share a slot`() = runBlocking {
        val backend = InMemoryBackend()
        cache(backend, account = 1L).write(FakeResp(1, "a"), null)

        assertNull(cache(backend, account = 2L).read())
        assertNotNull(cache(backend, account = 1L).read())
    }

    @Test
    fun `clear removes the snapshot`() = runBlocking {
        val backend = InMemoryBackend()
        val cache = cache(backend)
        cache.write(FakeResp(1, "a"), null)
        assertNotNull(cache.read())

        cache.clear()
        assertNull(cache.read())
    }

    @Test
    fun `different payload types on the same slot do not collide`() = runBlocking {
        val backend = InMemoryBackend()
        // 同 slot 同账号但不同 Resp 类型：键含类型名 → 落不同行，绝不把 FakeResp 的 JSON
        // 宽松反序列化进 OtherResp（静默串味），另一类型读回是干净未命中
        FeedFirstPageCache(
            slot = "s", type = FakeResp::class.java, maxAgeMillis = 60_000L,
            backend = backend, gson = Gson(), accountId = { 1L }, now = { 1_000L },
        ).write(FakeResp(1, "a"), null)

        val other = FeedFirstPageCache(
            slot = "s", type = OtherResp::class.java, maxAgeMillis = 60_000L,
            backend = backend, gson = Gson(), accountId = { 1L }, now = { 1_000L },
        )
        assertNull(other.read())
    }
}
