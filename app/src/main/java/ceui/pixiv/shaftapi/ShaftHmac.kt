package ceui.pixiv.shaftapi

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared HMAC-SHA256 helper for talking to shaft-api-v2.
 *
 * The server treats the secret as the ASCII bytes of the hex string itself
 * (matching Node's default `crypto.createHmac('sha256', secret)` behaviour),
 * **not** the 32 bytes you'd get by hex-decoding it. Don't try to decode the
 * key — pass the raw `BuildConfig.SHAFT_EVENTS_HMAC` string straight in.
 *
 * Both [EventReporter][ceui.pixiv.events.EventReporter] (HTTP /events/batch
 * body signature) and [chat][ceui.pixiv.chat] (WS upgrade-URL signature +
 * /chat/profile body signature) sign with this. Keeping it in one place
 * means a future tweak (e.g. switching key encoding) only happens once.
 */
object ShaftHmac {

    /**
     * Compute `HMAC_SHA256(secretAscii, payload)` and return the digest as
     * a lowercase hex string.
     *
     * Empty secret → empty signature, **not** an exception:`SecretKeySpec`
     * 对空 key 直接抛 `IllegalArgumentException`,而没配 `SHAFT_EVENTS_HMAC` 的
     * 构建(fork / 本地无 secret)`BuildConfig` 里就是空串——按 build.gradle 里
     * 写明的契约,这类构建应该拿到服务端 401 走各自的错误 UI,而不是一进广场/
     * 聊天就整个进程崩掉。空签名喂给服务端正好得到那个 401。
     */
    fun signHex(payload: String, secretAscii: String): String {
        if (secretAscii.isEmpty()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretAscii.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Chat-specific helper. The canonical payload for both the WS handshake
     * query string and the `POST /chat/profile` body signature is
     * `"${clientId}|${ts}"`.
     *
     * **`ts` must be the same decimal string used in the URL** — don't sign
     * the `Long` and then format it differently when embedding (no scientific
     * notation, no trailing `.0`, no leading whitespace). The server re-signs
     * the literal string it received from the wire, so any canonicalisation
     * drift between sign-time and put-on-wire becomes a `bad_sig` 401.
     */
    fun signClientIdTs(clientId: String, ts: String, secretAscii: String): String =
        signHex("$clientId|$ts", secretAscii)

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        val hex = "0123456789abcdef"
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4])
            out.append(hex[v and 0x0F])
        }
        return out.toString()
    }
}
