package ceui.pixiv.shaftapi

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaApiTest {
    @Test
    fun `complete response reads server mediaId field`() {
        val media = Gson().fromJson(
            """{"mediaId":"test-media","objectKey":"users/42/test.png","contentType":"image/png","size":12,"createdAt":1700000000000}""",
            MediaObject::class.java,
        )
        assertEquals("test-media", media.id)
        assertEquals(12L, media.size)
    }
}
