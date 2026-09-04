package ceui.pixiv.auth

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

public class AuthApiTest {

    private val gson = Gson()

    @Test
    public fun `bootstrap request uses stable wire field names`() {
        val json = gson.toJson(CreateSessionRequest(uid = 123L, deviceId = "device-123456789"))
        val fields = gson.fromJson(json, Map::class.java)

        assertEquals("app_hmac", fields["grant_type"])
        assertEquals(123.0, fields["uid"])
        assertEquals("device-123456789", fields["device_id"])
    }

    @Test
    public fun `token response reads identity and generation wire fields`() {
        val response = gson.fromJson(
            """{"uid":123,"generation":7,"token_type":"Bearer"}""",
            TokenResponse::class.java,
        )

        assertEquals(123L, response.uid)
        assertEquals(7L, response.generation)
        assertEquals("Bearer", response.tokenType)
    }
}
