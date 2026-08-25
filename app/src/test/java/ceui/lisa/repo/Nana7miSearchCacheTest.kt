package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.loxia.Nana7miSearchCacheLookupReq
import ceui.loxia.Nana7miSearchCacheLookupResp
import ceui.loxia.PixshaftApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

    // ── key：跨用户共享的前提是「同一个请求 → 同一个 key」 ──

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
    fun `popular sorts tolerate hours of staleness, date sorts only minutes`() {
        assertEquals(12L * 3_600_000L, Nana7miSearchCache.maxAgeMsFor("popular_desc"))
        assertEquals(12L * 3_600_000L, Nana7miSearchCache.maxAgeMsFor("popular_male_desc"))
        assertEquals(30L * 60_000L, Nana7miSearchCache.maxAgeMsFor("date_desc"))
        assertEquals(30L * 60_000L, Nana7miSearchCache.maxAgeMsFor(null))
    }

    // ── decode：命中页要能原样变回 Retrofit 会给的模型 ──

    @Test
    fun `a hit decodes into the same model a live search would produce`() {
        val body = Nana7miSearchCacheLookupResp(
            hit = true,
            page = JsonParser.parseString(
                """{"illusts":[{"id":101,"title":"one"},{"id":102,"title":"two"}],"next_url":"https://example.invalid/next"}""",
            ),
            storedAt = 1L,
            ageMs = 5L,
        )
        val page = Nana7miSearchCache.decode(body, ListIllust::class.java)!!
        assertEquals(2, page.illusts.size)
        assertEquals(102L, page.illusts[1].id)
        assertEquals("https://example.invalid/next", page.next_url)

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
    }

    // ── wire：请求体字段名和服务端 src/search-cache.js 一致 ──

    @Test
    fun `lookup posts uid, kind, key, maxAgeMs and page, and parses the page`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"hit":true,"page":{"illusts":[{"id":1}],"next_url":"n"},"storedAt":10,"ageMs":3,"serverTime":13}"""),
        )
        val key = "a".repeat(64)
        val resp = api.searchCacheLookupRaw(
            Nana7miSearchCacheLookupReq(uid = 42L, kind = "illust", key = key, maxAgeMs = 60_000L, page = "first"),
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/account/nana7mi/search-cache/lookup", recorded.path)
        val sent = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
        assertEquals(42L, sent["uid"].asLong)
        assertEquals("illust", sent["kind"].asString)
        assertEquals(key, sent["key"].asString)
        assertEquals(60_000L, sent["maxAgeMs"].asLong)
        assertEquals("first", sent["page"].asString)

        assertTrue(resp.isSuccessful)
        val page = Nana7miSearchCache.decode(resp.body(), ListIllust::class.java)!!
        assertEquals(1L, page.illusts[0].id)
        assertEquals("n", page.next_url)
    }
}
