package ceui.loxia

import ceui.lisa.models.ObjectSpec
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class NovelMigrationCompatibilityTest {

    private val gson = Gson()

    @Test
    fun `旧 Novel JSON 可直接读成唯一 Kotlin 模型`() {
        val json = """
            {
              "id": 11968607,
              "title": "legacy novel",
              "caption": "caption",
              "restrict": 0,
              "x_restrict": 1,
              "is_original": true,
              "image_urls": {
                "square_medium": "square",
                "medium": "medium",
                "large": "large"
              },
              "create_date": "2019-11-18T10:42:15+09:00",
              "tags": [{
                "name": "小説",
                "translated_name": "Novel",
                "added_by_uploaded_user": true
              }],
              "page_count": 1,
              "text_length": 2043,
              "user": {"id": 99, "name": "author", "is_followed": false},
              "series": {"id": 123, "title": "series"},
              "is_bookmarked": false,
              "total_bookmarks": 10,
              "total_view": 20,
              "visible": true,
              "is_muted": false,
              "novel_ai_type": 2,
              "contentOrder": "7",
              "isLocalSaved": true,
              "display_text": "legacy display"
            }
        """.trimIndent()

        val novel = gson.fromJson(json, Novel::class.java)

        assertEquals(11968607L, novel.id)
        assertEquals("legacy novel", novel.title)
        assertNull(novel.coverUrl)
        assertEquals("large", novel.resolvedCoverUrl())
        assertEquals("小説", novel.tags?.single()?.name)
        assertTrue(novel.tags?.single()?.added_by_uploaded_user == true)
        assertEquals(123L, novel.series?.id)
        assertEquals(99L, novel.user?.id)
        assertEquals(false, novel.is_bookmarked)
        assertTrue(novel.isCreatedByAI())
        assertEquals("7", novel.contentOrder)
        assertTrue(novel.isLocalSaved)
        assertEquals("legacy display", novel.display_text)
        assertEquals(ObjectSpec.KNovel, novel.objectType)
    }

    @Test
    fun `旧 coverUrl 优先且客户端热度字段不写进 JSON`() {
        val novel = gson.fromJson(
            """{"id":7,"coverUrl":"legacy-cover","image_urls":{"large":"api-cover"}}""",
            Novel::class.java,
        )
        novel.trendingScore = 12.5f

        assertEquals("legacy-cover", novel.coverUrl)
        val roundTrip = gson.toJson(novel)
        assertTrue(roundTrip.contains("\"coverUrl\":\"legacy-cover\""))
        assertFalse(roundTrip.contains("trendingScore"))
    }

    @Test
    fun `Starable 收藏更新和 copy 都操作同一个 Novel 类型`() {
        val novel = Novel(id = 42L)

        assertNull(novel.is_bookmarked)
        novel.setItemStared(true)
        assertTrue(novel.isItemStared())
        assertEquals(42, novel.getItemID())
        assertEquals(false, novel.withBookmarked(false).is_bookmarked)
    }

    @Test
    fun `统一 Novel 仍可通过 Java Serializable 传递`() {
        val source = Novel(
            id = 88L,
            title = "serialized",
            user = User(id = 9L, name = "author"),
            tags = listOf(Tag(name = "tag")),
        )
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(source) }
            output.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Novel }

        assertEquals(source, restored)
        assertEquals("author", restored.user?.name)
    }
}
