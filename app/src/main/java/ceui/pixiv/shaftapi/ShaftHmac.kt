package ceui.pixiv.shaftapi

/**
 * HMAC-SHA256 signer for shaft-api-v2.
 *
 * The build secret is never emitted into BuildConfig or returned to managed code. Gradle writes
 * masked byte tables under app/build/, and libshaft_secrets.so reconstructs the bytes only for the
 * duration of one HMAC operation before wiping its temporary buffers. Kotlin receives only the
 * lowercase hexadecimal digest.
 *
 * An embedded client cannot make a long-lived shared secret truly non-extractable; this native
 * boundary is static-analysis hardening. A server-issued short-lived key remains the stronger
 * protocol-level design.
 */
object ShaftHmac {

    private val nativeOperational: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary("shaft_secrets")
            nativeSelfTest()
        }.getOrDefault(false)
    }

    /** False for fork/dev builds with no injected secret, or if the native signer cannot load. */
    val isConfigured: Boolean
        get() = nativeOperational && runCatching { nativeIsConfigured() }.getOrDefault(false)

    /**
     * Computes `HMAC_SHA256(secretUtf8, payloadUtf8)` inside libshaft_secrets.so.
     *
     * Empty/unavailable native configuration deliberately returns an empty signature. The server
     * then rejects privileged calls while fork builds keep running instead of crashing.
     */
    fun signHex(payload: String): String {
        if (!nativeOperational) return ""
        return runCatching { nativeSignUtf8(payload) }.getOrDefault("")
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
    fun signClientIdTs(clientId: String, ts: String): String = signHex("$clientId|$ts")

    internal val isOperationalForTest: Boolean
        get() = nativeOperational

    private external fun nativeIsConfigured(): Boolean

    private external fun nativeSelfTest(): Boolean

    private external fun nativeSignUtf8(payload: String): String
}
