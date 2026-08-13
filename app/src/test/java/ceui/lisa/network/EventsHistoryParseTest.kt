package ceui.lisa.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonToken
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1010 回归：/events/history 里上报时没带 payload 的事件，服务端会返回
 * `"meta": null`。meta 声明为 JsonObject 时 Gson 把 JsonNull 强转失败抛
 * JsonSyntaxException，整页操作记录报「服务器返回的数据无效」。这里按
 * Retrofit GsonResponseBodyConverter 的同款路径（newJsonReader + adapter.read）
 * 验证 null / 缺失 / 正常对象三种 meta 都能解析。
 */
class EventsHistoryParseTest {

    private val gson = Gson()

    private fun parse(json: String): ShaftApiV2.EventsHistoryResponse {
        val adapter = gson.getAdapter(ShaftApiV2.EventsHistoryResponse::class.java)
        val reader = gson.newJsonReader(StringReader(json))
        val resp = adapter.read(reader)
        assertEquals(JsonToken.END_DOCUMENT, reader.peek())
        return resp
    }

    @Test
    fun `meta null does not fail the whole page`() {
        val resp = parse(
            """
            {"client_id":"c","limit":50,"event_type":null,"items":[
              {"id":3,"ts":1,"event_type":"bookmark","target_type":"illust","target_id":11,
               "platform":"android","channel":"github","app_version":"4.8.5",
               "meta":{"id":11,"title":"t"}},
              {"id":2,"ts":1,"event_type":"bookmark","target_type":"illust","target_id":12,
               "platform":"android","channel":"github","app_version":"4.8.5","meta":null},
              {"id":1,"ts":1,"event_type":"follow","target_type":"user","target_id":13,
               "platform":"android","channel":"github","app_version":"4.8.5"}
            ],"next_before":1}
            """.trimIndent()
        )
        assertEquals(3, resp.items.size)
        assertTrue(resp.items[0].meta is JsonObject)
        // JSON null 被 Gson 读成 JsonNull 实例，消费方 `as? JsonObject` 过滤
        assertNull(resp.items[1].meta as? JsonObject)
        // 服务端修复后 key 直接省略 → Java null
        assertNull(resp.items[2].meta)
        assertEquals(1L, resp.next_before)
    }
}
