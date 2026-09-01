package ceui.pixiv.ui.search

import ceui.pixiv.api.model.WebNovelSearchBody
import ceui.pixiv.api.model.WebResponse
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #1016：`/ajax/search/novels/{word}?gs=1` 的解析回归。
 *
 * 这条接口最容易悄悄坏的地方是**字段名**——它和 app-api 完全不是一套命名，而且返回的是
 * 系列条目与单篇条目的混合列表，两者靠 `novelId` 区分：
 *   - 系列条目的 `id` 是小说系列 id（能直接喂系列详情页）；
 *   - 单篇条目的 `id` 是 pixiv 给单篇造的 collection id，**不是**小说 id，真 id 在 `novelId` 上。
 * 认错了就会拿 collection id 去开小说详情，稳定 404。
 *
 * 下面的 JSON 是 2026-08-14 从线上按 `word=原神&gs=1` 抓的真实响应，逐字段裁剪保留。
 */
class WebNovelSearchParseTest {

    private val gson = Gson()

    private val json = """
    {
      "error": false,
      "body": {
        "novel": {
          "data": [
            {
              "id": "16351430",
              "title": "子供ローエンと同居人ファルカ",
              "cover": {"urls": {
                "240mw": "https://i.pximg.net/c/240x480_80/sci16351430_master1200.jpg",
                "480mw": "https://i.pximg.net/c/480x960/sci16351430_master1200.jpg",
                "original": "https://i.pximg.net/novel-cover-original/sci16351430.png"
              }},
              "tags": ["ルカエン", "原神BL"],
              "xRestrict": 0,
              "genre": "0",
              "createDateTime": "2026-08-14T12:00:28+09:00",
              "userId": "23207401",
              "userName": "NoA",
              "profileImageUrl": "https://i.pximg.net/user-profile/img/25244821_170.jpg",
              "bookmarkCount": 7,
              "isOneshot": false,
              "caption": "",
              "isConcluded": true,
              "episodeCount": 12,
              "publishedEpisodeCount": 11,
              "latestPublishDateTime": "2026-08-14T12:00:47+09:00",
              "latestEpisodeId": "28859851",
              "textLength": 2876,
              "publishedTextLength": 2500,
              "aiType": 1
            },
            {
              "id": "16351395",
              "title": "積み立てドゥリ放",
              "cover": {"urls": {"480mw": "https://i.pximg.net/c/480x960/ci28859767_master1200.jpg"}},
              "tags": ["ドゥリ放"],
              "xRestrict": 1,
              "createDateTime": "2026-08-14T11:46:01+09:00",
              "userId": "37534324",
              "userName": "わたぼこり",
              "bookmarkCount": 3,
              "isOneshot": true,
              "publishedDateTime": "2026-08-14T11:46:01+09:00",
              "novelId": "28859767",
              "textLength": 5779,
              "publishedTextLength": 5779,
              "aiType": 2,
              "bookmarkData": null
            }
          ],
          "total": 32431,
          "lastPage": 10
        }
      }
    }
    """.trimIndent()

    private fun parse(): WebResponse<WebNovelSearchBody> {
        val type = object : TypeToken<WebResponse<WebNovelSearchBody>>() {}.type
        return gson.fromJson(json, type)
    }

    @Test
    fun `parses section paging fields`() {
        val section = parse().body?.novel
        assertNotNull(section)
        assertEquals(32431, section!!.total)
        assertEquals(10, section.lastPage)
        assertEquals(2, section.data?.size)
    }

    @Test
    fun `series entry keeps series id and episode counts`() {
        val row = parse().body!!.novel!!.data!![0]
        assertNull("系列条目不该有 novelId，否则会被当成单篇", row.novelId)
        assertEquals("16351430", row.id)
        assertEquals(12, row.episodeCount)
        assertEquals(11, row.publishedEpisodeCount)
        assertTrue(row.isConcluded)

        val novel = row.toNovel(row.id!!.toLong(), asSeries = true)
        assertEquals(16351430L, novel.id)
        // 系列卡的日期取最新一话，字数取「已公开」那份（未公开的付费话不算）
        assertEquals("2026-08-14T12:00:47+09:00", novel.create_date)
        assertEquals(2500, novel.text_length)
        assertEquals(16351430L, novel.series?.id)
        assertEquals(23207401L, novel.user?.id)
        assertEquals(7, novel.total_bookmarks)
        assertEquals("https://i.pximg.net/c/480x960/sci16351430_master1200.jpg", novel.image_urls?.large)
        assertEquals(listOf("ルカエン", "原神BL"), novel.tags?.map { it.name })
        assertEquals(false, novel.is_bookmarked)
    }

    @Test
    fun `single entry maps novelId not collection id`() {
        val row = parse().body!!.novel!!.data!![1]
        assertEquals("28859767", row.novelId)
        // id 是 collection id，和系列 id 同号段——用它开小说详情必然 404
        assertEquals("16351395", row.id)

        val novel = row.toNovel(row.novelId!!.toLong())
        assertEquals(28859767L, novel.id)
        assertNull("单篇不该凭空多出一个系列", novel.series)
        assertEquals("2026-08-14T11:46:01+09:00", novel.create_date)
        assertEquals(1, novel.x_restrict)
        assertEquals(2, novel.novel_ai_type)
        assertNull("网页没给头像时不该造一个空 ImageUrls", novel.user?.profile_image_urls)
    }
}
