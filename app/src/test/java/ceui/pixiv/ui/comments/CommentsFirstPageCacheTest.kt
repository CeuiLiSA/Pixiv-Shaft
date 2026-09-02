package ceui.pixiv.ui.comments

import ceui.pixiv.api.model.Comment
import ceui.pixiv.api.model.CommentResponse
import ceui.pixiv.api.model.ObjectType
import ceui.loxia.User
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [CommentsFirstPageCache]：存取 / 过期 / 失效 / LRU 上限 / [CommentsFirstPageCache.storeFor]
 * 句柄与 feed 源契约的桥接。纯内存，不碰 Android。
 */
class CommentsFirstPageCacheTest {

    private var now = 1_000L
    private val cache = CommentsFirstPageCache(maxAgeMillis = 60_000L, maxEntries = 2, now = { now })
    private val illust1 = CommentTarget(1L, ObjectType.ILLUST)

    private fun page(vararg ids: Long, next: String? = null) = CommentResponse(
        comments = ids.map { Comment(id = it, user = User(id = it)) },
        next_url = next,
    )

    @Test
    fun `put then get hands back the same response with its next cursor`() {
        val response = page(1, 2, next = "next-url")
        cache.put(illust1, response)

        val hit = checkNotNull(cache.get(illust1))
        assertSame(response, hit.payload)
        assertEquals("next-url", hit.nextCursor)
        assertEquals(1_000L, hit.savedAtMillis)
    }

    @Test
    fun `empty next_url is normalised to a null cursor`() {
        cache.put(illust1, page(1, next = ""))
        assertNull(cache.get(illust1)?.nextCursor)
    }

    @Test
    fun `expired entry is a miss and gets dropped`() {
        cache.put(illust1, page(1))
        now += 60_000L
        assertNotNull("刚好到期仍命中", cache.get(illust1))
        now += 1L
        assertNull(cache.get(illust1))
        now = 1_000L
        assertNull("过期条目应已被清掉，回拨时间也不复活", cache.get(illust1))
    }

    @Test
    fun `invalidate drops only that target`() {
        val novel1 = CommentTarget(1L, ObjectType.NOVEL)
        cache.put(illust1, page(1))
        cache.put(novel1, page(2))

        cache.invalidate(illust1)

        assertNull(cache.get(illust1))
        assertNotNull("同 id 不同类型是不同对象", cache.get(novel1))
    }

    @Test
    fun `least recently used entry is evicted past maxEntries`() {
        val t2 = CommentTarget(2L, ObjectType.ILLUST)
        val t3 = CommentTarget(3L, ObjectType.ILLUST)
        cache.put(illust1, page(1))
        cache.put(t2, page(2))
        cache.get(illust1) // 触碰 1，让 2 成为最久未用
        cache.put(t3, page(3))

        assertNotNull(cache.get(illust1))
        assertNull(cache.get(t2))
        assertNotNull(cache.get(t3))
    }

    @Test
    fun `storeFor bridges write and read for one target`() = runBlocking {
        val store = cache.storeFor(illust1)
        assertNull(store.read())

        val response = page(1)
        store.write(response, "gated-cursor")

        val viaStore = checkNotNull(store.read())
        assertSame(response, viaStore.payload)
        assertEquals("feed 源交来的游标优先于响应自带的 next_url", "gated-cursor", viaStore.nextCursor)
        assertSame(viaStore, cache.get(illust1))
    }
}
