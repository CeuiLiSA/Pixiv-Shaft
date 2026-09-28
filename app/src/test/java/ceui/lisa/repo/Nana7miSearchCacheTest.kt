package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.shaftapi.Nana7miSearchCacheLookupReq
import ceui.pixiv.shaftapi.Nana7miSearchCacheLookupResp
import ceui.pixiv.shaftapi.Nana7miSearchCacheStoreReq
import ceui.pixiv.shaftapi.PixshaftApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate

class Nana7miSearchCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PixshaftApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(PixshaftApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── key：同一 UID 复用的前提是「同一个请求 → 同一个 key」 ──

    @Test
    fun `same request from two callers yields the same key`() {
        val a = Nana7miSearchCache.firstPageKey(
            Nana7miSearchCache.Kind.ILLUST,
            listOf("word" to "原神", "sort" to "popular_desc", "bookmark_num_min" to 1000),
        )
        val b = Nana7miSearchCache.firstPageKey(
            Nana7miSearchCache.Kind.ILLUST,
            listOf("word" to "原神", "sort" to "popular_desc", "bookmark_num_min" to 1000),
        )
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `any differing parameter, kind or page cursor is a different key`() {
        val base = listOf("word" to "原神", "sort" to "popular_desc", "search_ai_type" to 0)
        val key = Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, base)
        assertNotEquals(
            key,
            Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, base.map { if (it.first == "search_ai_type") it.first to 1 else it }),
        )
        assertNotEquals(
            key,
            Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, base.map { if (it.first == "sort") it.first to "date_desc" else it }),
        )
        assertNotEquals(key, Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.NOVEL, base))
        assertNotEquals(
            key,
            Nana7miSearchCache.nextPageKey(Nana7miSearchCache.Kind.ILLUST, "https://example.invalid/v1/search/illust?word=原神&offset=30"),
        )
    }

    @Test
    fun `a null parameter is absent, not an empty string, and values cannot collide across names`() {
        val absent = Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, listOf("word" to "a", "tool" to null))
        val empty = Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, listOf("word" to "a", "tool" to ""))
        assertNotEquals(absent, empty)
        // 值里带分隔符也不能拼出另一组参数的规范串。
        val smuggled = Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, listOf("word" to "a|tool=x"))
        val honest = Nana7miSearchCache.firstPageKey(Nana7miSearchCache.Kind.ILLUST, listOf("word" to "a", "tool" to "x"))
        assertNotEquals(smuggled, honest)
    }

    @Test
    fun `popular sort tolerance shrinks as the window reaches closer to today`() {
        val today = LocalDate.of(2026, 9, 28)
        fun popular(start: String?, end: String?, sort: String = "popular_desc") =
            Nana7miSearchCache.maxAgeMsFor(sort, start, end, today)
        val hour = 3_600_000L
        // 点 tag 进搜索页的默认档：不限期间
        assertEquals(7 * 24 * hour, popular(null, null))
        assertEquals(7 * 24 * hour, popular(null, null, sort = "popular_male_desc"))
        assertEquals("only an end bound is still all-time", 7 * 24 * hour, popular(null, "2026-09-28"))
        // 相对档，按 DurationBucket.toDateRange 的实际输出
        val range = { b: DurationBucket -> b.toDateRange(today) }
        assertEquals(30 * 60_000L, range(DurationBucket.Last24Hours).let { popular(it.first, it.second) })
        assertEquals(2 * hour, range(DurationBucket.LastWeek).let { popular(it.first, it.second) })
        assertEquals(12 * hour, range(DurationBucket.LastMonth).let { popular(it.first, it.second) })
        assertEquals(24 * hour, range(DurationBucket.LastHalfYear).let { popular(it.first, it.second) })
        assertEquals(24 * hour, range(DurationBucket.LastYear).let { popular(it.first, it.second) })
        // 自定义期间
        assertEquals(30 * 60_000L, popular("2026-09-28", "2026-09-28"))
        assertEquals(7 * 24 * hour, popular("2020-01-01", "2026-09-28"))
        assertEquals("a window that closed a month ago is settled", 7 * 24 * hour, popular("2026-08-01", "2026-08-10"))
        assertEquals(2 * hour, popular("2026-09-22", "2026-09-24"))
        // 解析不了就最严
        assertEquals(30 * 60_000L, popular("garbage", "2026-09-28"))
        assertEquals(30 * 60_000L, popular(null, "garbage"))
    }

    @Test
    fun `date sorts stay fresh unless the whole window ended over a week ago`() {
        val today = LocalDate.of(2026, 9, 28)
        val fresh = 30 * 60_000L
        assertEquals(fresh, Nana7miSearchCache.maxAgeMsFor("date_desc", null, null, today))
        assertEquals(fresh, Nana7miSearchCache.maxAgeMsFor("date_asc", "2020-01-01", null, today))
        assertEquals(fresh, Nana7miSearchCache.maxAgeMsFor("date_desc", "2026-09-01", "2026-09-21", today))
        assertEquals(24 * 3_600_000L, Nana7miSearchCache.maxAgeMsFor("date_desc", "2026-09-01", "2026-09-20", today))
        assertEquals(fresh, Nana7miSearchCache.maxAgeMsFor(null, null, null, today))
    }

    // ── decode：命中页要能原样变回 Retrofit 会给的模型 ──

    @Test
    fun `a hit decodes into the same model a live search would produce`() {
        val body = Nana7miSearchCacheLookupResp(
            hit = true,
            page = JsonParser.parseString(
                """{"illusts":[{"id":101,"title":"one"},{"id":102,"title":"two"}],"next_url":"https://app-api.pixiv.net/v1/search/illust?offset=30"}""",
            ),
            storedAt = 1L,
            ageMs = 5L,
        )
        val page = Nana7miSearchCache.decode(body, ListIllust::class.java)!!
        assertEquals(2, page.illusts.size)
        assertEquals(102L, page.illusts[1].id)
        assertEquals("https://app-api.pixiv.net/v1/search/illust?offset=30", page.next_url)

        val novel = Nana7miSearchCache.decode(
            Nana7miSearchCacheLookupResp(hit = true, page = JsonParser.parseString("""{"novels":[{"id":7}],"next_url":null}""")),
            ListNovel::class.java,
        )!!
        assertEquals(1, novel.novels.size)
        assertNull(novel.nextUrl)
    }

    @Test
    fun `anything that is not a clean hit is a miss`() {
        assertNull(Nana7miSearchCache.decode(null, ListIllust::class.java))
        assertNull(Nana7miSearchCache.decode(Nana7miSearchCacheLookupResp(hit = false), ListIllust::class.java))
        assertNull(Nana7miSearchCache.decode(Nana7miSearchCacheLookupResp(hit = true, page = null), ListIllust::class.java))
        assertNull(
            Nana7miSearchCache.decode(
                Nana7miSearchCacheLookupResp(hit = true, page = JsonParser.parseString("[1,2]")),
                ListIllust::class.java,
            ),
        )
        assertNull(
            Nana7miSearchCache.decode(
                Nana7miSearchCacheLookupResp(hit = true, page = JsonParser.parseString("""{"illusts":"nope"}""")),
                ListIllust::class.java,
            ),
        )
        // 列表字段缺失：解析得出来，但交给 Mapper 会 NPE，所以也是 miss。
        assertNull(
            Nana7miSearchCache.decode(
                Nana7miSearchCacheLookupResp(hit = true, page = JsonParser.parseString("""{"next_url":"n"}""")),
                ListIllust::class.java,
            ),
        )
        assertNull(
            Nana7miSearchCache.decode(
                Nana7miSearchCacheLookupResp(
                    hit = true,
                    page = JsonParser.parseString(
                        """{"illusts":[{"id":1}],"next_url":"https://attacker.example/steal"}""",
                    ),
                ),
                ListIllust::class.java,
            ),
        )
    }

    // ── wire：请求体字段名和服务端 src/search-cache.js 一致 ──

    @Test
    fun `lookup posts uid, kind, key, maxAgeMs, page and requestId, and parses the page`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hit":true,"page":{"illusts":[{"id":1}],"next_url":"https://app-api.pixiv.net/v1/search/illust?offset=30"},"storedAt":10,"ageMs":3,"serverTime":13}"""),
        )
        val key = "a".repeat(64)
        val requestId = "823e4567-e89b-42d3-a456-426614174010"
        val resp = api.searchCacheLookupRaw(
            Nana7miSearchCacheLookupReq(
                uid = 42L,
                kind = "illust",
                key = key,
                maxAgeMs = 60_000L,
                page = "first",
                requestId = requestId,
            ),
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/account/nana7mi/search-cache/lookup", recorded.path)
        val sent = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
        assertEquals(42L, sent["uid"].asLong)
        assertEquals("illust", sent["kind"].asString)
        assertEquals(key, sent["key"].asString)
        assertEquals(60_000L, sent["maxAgeMs"].asLong)
        assertEquals("first", sent["page"].asString)
        assertEquals(requestId, sent["requestId"].asString)

        assertTrue(resp.isSuccessful)
        val page = Nana7miSearchCache.decode(resp.body(), ListIllust::class.java)!!
        assertEquals(1L, page.illusts[0].id)
        assertEquals("https://app-api.pixiv.net/v1/search/illust?offset=30", page.next_url)
    }

    @Test
    fun `legacy capability omits requestId from cache lookup body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hit":false}"""),
        )

        api.searchCacheLookupRaw(
            Nana7miSearchCacheLookupReq(
                uid = 42L,
                kind = "illust",
                key = "b".repeat(64),
                maxAgeMs = 60_000L,
                page = "first",
                requestId = null,
            ),
        )

        val sent = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertFalse(sent.has("requestId"))
    }

    @Test
    fun `store echoes the opaque one-time receipt from a miss`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"stored":true}"""),
        )
        val token = "opaque.server.receipt"
        val page = JsonParser.parseString("""{"illusts":[{"id":1}],"next_url":null}""")

        val response = api.searchCacheStoreRaw(
            Nana7miSearchCacheStoreReq(
                uid = 42L,
                kind = "illust",
                key = "c".repeat(64),
                page = page,
                storeToken = token,
            ),
        )

        assertTrue(response.isSuccessful)
        assertTrue(response.body()?.stored == true)
        val sent = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(token, sent["storeToken"].asString)
        assertEquals(1L, sent["page"].asJsonObject["illusts"].asJsonArray[0].asJsonObject["id"].asLong)
    }
}
