package ceui.pixiv.chat.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the reply-to-message feature (`reply_to` on `msg`
 * frames, both directions) — pinned against `shaft-api-v2/src/chat/ws.js`
 * (`parseReplyTo` / `handleMsg`) and `docs/ws-chat-integration.md` §3.
 */
class ChatFrameReplyToTest {

    // ── Inbound (server → client) ────────────────────────────────────────

    @Test
    fun `msg frame with reply_to decodes the quoted snapshot`() {
        val raw = """
            {"kind":"msg","room":"global","uid":7,"display_name":"lisa",
             "client_msg_id":"cmid-reply-0001","text":"同意","ts":1700000000000,
             "reply_to":{"uid":5,"client_msg_id":"cmid-orig-0001","display_name":"nana","text":"原消息"}}
        """.trimIndent()
        val frame = ChatFrameDecoder.decode(raw)
        assertTrue(frame is ChatFrame.Msg)
        val ref = (frame as ChatFrame.Msg).replyTo
        assertEquals(ChatReplyRef(uid = 5, clientMsgId = "cmid-orig-0001", displayName = "nana", text = "原消息"), ref)
    }

    @Test
    fun `reply_to with null text means original purged - still decodes`() {
        val raw = """
            {"kind":"msg","room":"global","uid":7,"client_msg_id":"cmid-reply-0002","text":"x","ts":1,
             "reply_to":{"uid":5,"client_msg_id":"cmid-orig-0002","display_name":"匿名_5","text":null}}
        """.trimIndent()
        val ref = (ChatFrameDecoder.decode(raw) as ChatFrame.Msg).replyTo
        assertEquals("cmid-orig-0002", ref?.clientMsgId)
        assertNull(ref?.text)
    }

    @Test
    fun `msg frame without reply_to has null replyTo`() {
        val raw = """{"kind":"msg","room":"global","uid":7,"client_msg_id":"cmid-plain-0001","text":"x","ts":1}"""
        assertNull((ChatFrameDecoder.decode(raw) as ChatFrame.Msg).replyTo)
    }

    @Test
    fun `malformed reply_to degrades to null instead of dead-lettering the frame`() {
        // Missing client_msg_id → can't anchor the quote; the message itself must still render.
        val raw = """{"kind":"msg","room":"global","uid":7,"client_msg_id":"cmid-plain-0002","text":"x","ts":1,
                      "reply_to":{"uid":5}}"""
        val frame = ChatFrameDecoder.decode(raw)
        assertTrue(frame is ChatFrame.Msg)
        assertNull((frame as ChatFrame.Msg).replyTo)
        // Non-object reply_to likewise.
        val raw2 = """{"kind":"msg","room":"global","uid":7,"client_msg_id":"cmid-plain-0003","text":"x","ts":1,
                       "reply_to":"nope"}"""
        assertNull((ChatFrameDecoder.decode(raw2) as ChatFrame.Msg).replyTo)
    }

    // ── Outbound (client → server) ───────────────────────────────────────

    @Test
    fun `msgGlobal emits reply_to with only uid and client_msg_id`() {
        val json = ChatFrameEncoder.msgGlobal(
            clientMsgId = "cmid-reply-0003",
            text = "同意",
            replyTo = ChatReplyRef(uid = 5, clientMsgId = "cmid-orig-0003", displayName = "ignored", text = "ignored"),
        )
        val o = JSONObject(json)
        assertEquals("msg", o.getString("kind"))
        assertEquals("global", o.getString("room"))
        val rt = o.getJSONObject("reply_to")
        assertEquals(5L, rt.getLong("uid"))
        assertEquals("cmid-orig-0003", rt.getString("client_msg_id"))
        // Snapshot fields are server-owned — never sent.
        assertFalse(rt.has("display_name"))
        assertFalse(rt.has("text"))
        assertEquals(2, rt.length())
    }

    @Test
    fun `msg1v1 emits reply_to and escapes the quoted id`() {
        val json = ChatFrameEncoder.msg1v1(
            toUid = 99,
            clientMsgId = "cmid-reply-0004",
            text = "ok",
            replyTo = ChatReplyRef(uid = 5, clientMsgId = "a\"b-0123456"),
        )
        val o = JSONObject(json)
        assertEquals(99L, o.getLong("to_uid"))
        assertEquals("a\"b-0123456", o.getJSONObject("reply_to").getString("client_msg_id"))
    }

    @Test
    fun `no replyTo means no reply_to key on the wire`() {
        val o = JSONObject(ChatFrameEncoder.msgGlobal("cmid-plain-0004", "hi"))
        assertFalse(o.has("reply_to"))
    }
}
