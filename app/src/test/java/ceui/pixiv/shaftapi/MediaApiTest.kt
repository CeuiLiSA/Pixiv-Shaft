package ceui.pixiv.shaftapi

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaApiTest {
    @Test
    fun `complete response includes metadata and signed preview without a second request`() {
        val media = Gson().fromJson(
            """{"mediaId":"test-media","objectKey":"users/42/test.png","contentType":"image/png","size":12,"width":4,"height":3,"createdAt":1700000000000,"url":"https://media.pixshaft.com/users/42/test.png?q-signature=test-signature","expiresAt":1700000300000}""",
            MediaObject::class.java,
        )
        assertEquals("test-media", media.id)
        assertEquals(12L, media.size)
        assertEquals(4, media.width)
        assertEquals(3, media.height)
        assertEquals("https://media.pixshaft.com/users/42/test.png?q-signature=test-signature", media.url)
        assertEquals(1700000300000L, media.expiresAt)
    }
}
