package ceui.pixiv.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class WebTopApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PixivWebApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(PixivWebApi::class.java)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun artwork(id: Long, restriction: Int = 1, masked: Boolean = false) = """{
        "id":"$id", "userId":"321", "title":"Title", "userName":"Artist", "illustType":0,
        "url":"https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/2026/09/04/15/53/34/${id}_p0_custom1200.jpg",
        "width":600, "height":900, "pageCount":2, "xRestrict":$restriction, "aiType":1,
        "tags":["R-18"], "bookmarkData":null, "isMasked":$masked,
        "urls":{"250x250":"https://i.pximg.net/x.jpg"}, "titleCaptionTranslation":{"workTitle":null}
    }"""

    // 字段形状取自 2026-10-08 实测：区块 id 多为字符串、follow 是数字、rank 是字符串，且夹带大量用不到的区块。
    private fun enqueueTop() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{
            "error":false, "message":"",
            "body":{
                "page":{
                    "recommend":{"ids":["103","101","999"],"details":{"103":{"methods":["x"],"score":7.6}}},
                    "recommendByTag":[{"tag":"ブルーアーカイブ","ids":["102","101"]}],
                    "follow":[104,101],
                    "ranking":{"date":"20261007","items":[{"rank":"1","id":"102"},{"rank":"2","id":"105"}]},
                    "myFavoriteTags":["ロリ"],
                    "pixivision":[{"id":"11674","title":"t"}]
                },
                "thumbnails":{
                    "illust":[${artwork(101)},${artwork(102)},${artwork(103)},${artwork(104, restriction = 0)},${artwork(105, masked = true)}],
                    "novel":[]
                },
                "tagTranslation":{}, "users":[], "zoneConfig":{}
            }
        }"""))
    }

    @Test fun `top request carries kind, mode and lang`() = runBlocking {
        enqueueTop()
        api.getTopArtworks("manga", "r18")
        val request = server.takeRequest()
        assertEquals("/ajax/top/manga", request.requestUrl!!.encodedPath)
        assertEquals("r18", request.requestUrl!!.queryParameter("mode"))
        assertEquals("zh", request.requestUrl!!.queryParameter("lang"))
    }

    @Test fun `sections resolve to thumbnails in section order`() = runBlocking {
        enqueueTop()
        val response = api.getTopArtworks("illust", "r18")
        val body = response.body!!
        val page = body.page!!

        assertEquals(false, response.error)
        // 999 不在 thumbnails 里，跳过而不是占位
        assertEquals(listOf(103L, 101L), body.artworks(page.recommend?.ids).map { it.id })
        assertEquals("ブルーアーカイブ", page.recommendByTag!!.single().tag)
        assertEquals(listOf(102L, 101L), body.artworks(page.recommendByTag!!.single().ids).map { it.id })
        assertEquals(listOf(104L, 101L), body.artworks(page.follow).map { it.id })
        // 105 被遮罩，排行榜里只剩 102
        assertEquals(listOf(1), page.ranking!!.items!!.map { it.rank }.take(1))
        assertEquals(listOf(102L), body.artworks(page.ranking!!.items!!.map { it.id }).map { it.id })
    }

    @Test fun `resolved artwork keeps age rating and builds medium url`() = runBlocking {
        enqueueTop()
        val body = api.getTopArtworks("illust", "r18").body!!
        val illust = body.artworks(listOf(101L)).single().toIllust()

        assertEquals(1, illust.x_restrict)
        assertEquals("Artist", illust.user?.name)
        assertEquals(
            "https://i.pximg.net/c/540x540_70/img-master/img/2026/09/04/15/53/34/101_p0_master1200.jpg",
            illust.image_urls?.medium,
        )
        assertTrue(body.artworks(null).isEmpty())
    }
}
