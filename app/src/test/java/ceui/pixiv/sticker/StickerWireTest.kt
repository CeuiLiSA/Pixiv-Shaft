package ceui.pixiv.sticker

import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
import ceui.pixiv.chat.api.ChatFrameEncoder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class StickerWireTest {
    @Test fun `Glide resource key follows requested archive size independently of screen pixels`() {
        val loader = StickerModelLoader()
        val small = LocalSticker(650863185465585230L, "generation", 64)
        val large = small.copy(resourceSize = 128)
        val options = com.bumptech.glide.load.Options()
        val lowDensity = loader.buildLoadData(small, 72, 72, options)
        val highDensity = loader.buildLoadData(small, 216, 216, options)
        val message = loader.buildLoadData(large, 216, 216, options)
        assertEquals(lowDensity.sourceKey, highDensity.sourceKey)
        assertNotEquals(highDensity.sourceKey, message.sourceKey)
        assertEquals(com.bumptech.glide.load.DataSource.LOCAL, message.fetcher.dataSource)
    }

    @Test fun `chat sends exact string sticker IDs in both room types`() {
        for (json in listOf(
            ChatFrameEncoder.msgGlobal("client-id-123", "[Sticker]", stickerId = 650863185465585230L),
            ChatFrameEncoder.msg1v1(42, "client-id-123", "[Sticker]", stickerId = 650863185465585230L),
        )) {
            val value = JsonParser.parseString(json).asJsonObject.get("sticker_id")
            assertTrue(value.asJsonPrimitive.isString)
            assertEquals("650863185465585230", value.asString)
        }
    }
}
