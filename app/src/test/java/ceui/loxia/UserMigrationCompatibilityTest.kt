package ceui.loxia

import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserPreviewsBean
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ObjectStreamClass

class UserMigrationCompatibilityTest {

    private val gson = Gson()

    @Test
    fun `4_8_9 用户快照在 getUserId 改签后仍可反序列化`() {
        assertEquals(3488682966520018560L, serialVersionUid(User::class.java))
        assertEquals(1370286510125849777L, serialVersionUid(AccountResponse::class.java))
        assertEquals(-9038046041993882463L, serialVersionUid(UserDetailResponse::class.java))
        assertEquals(-782402126830490604L, serialVersionUid(UserPreviewsBean::class.java))
    }

    private fun serialVersionUid(type: Class<*>): Long =
        ObjectStreamClass.lookup(type).serialVersionUID

    @Test
    fun `旧用户快照可直接读成唯一 User 模型`() {
        val json = """
            {
              "profile_image_urls": {
                "px_16x16": "small",
                "px_50x50": "medium",
                "px_170x170": "large"
              },
              "id": 31655571,
              "name": "details",
              "comment": "bio",
              "account": "mercisbv",
              "password": "secret",
              "mail_address": "user@example.com",
              "is_login": true,
              "is_premium": false,
              "is_followed": true,
              "is_access_blocking_user": false,
              "is_accept_request": true,
              "lastTokenTime": 123456789,
              "x_restrict": 2,
              "is_mail_authorized": true,
              "require_policy_agreement": false
            }
        """.trimIndent()

        val user = gson.fromJson(json, User::class.java)

        assertEquals(31655571L, user.id)
        assertEquals("details", user.name)
        assertEquals("bio", user.comment)
        assertEquals("mercisbv", user.account)
        assertEquals("secret", user.password)
        assertEquals("user@example.com", user.mail_address)
        assertTrue(user.is_login)
        assertEquals(false, user.is_premium)
        assertEquals(true, user.is_followed)
        assertEquals(false, user.is_access_blocking_user)
        assertEquals(true, user.is_accept_request)
        assertEquals(123456789L, user.lastTokenTime)
        assertTrue(user.isR18Enabled())
        assertTrue(user.isR18GEnabled())
        assertEquals("large", user.profile_image_urls?.findMaxSizeUrl())
    }

    @Test
    fun `旧账号 JSON 可直接读成 AccountResponse 且保留本地字段`() {
        val json = """
            {
              "access_token": "access",
              "expires_in": 3600,
              "token_type": "bearer",
              "scope": "all",
              "refresh_token": "refresh",
              "device_token": "device",
              "local_user": "local",
              "user": {
                "id": 42,
                "name": "tester",
                "is_login": true,
                "lastTokenTime": 987654321
              }
            }
        """.trimIndent()

        val account = gson.fromJson(json, AccountResponse::class.java)

        assertEquals("access", account.access_token)
        assertEquals(3600, account.expires_in)
        assertEquals("refresh", account.refresh_token)
        assertEquals("device", account.device_token)
        assertEquals("local", account.local_user)
        assertEquals(42, account.getUserId())
        assertTrue(account.user?.is_login == true)
        assertEquals(987654321L, account.user?.lastTokenTime)
    }

    @Test
    fun `历史小说里的旧用户 JSON 也复用 User`() {
        val novel = gson.fromJson(
            """{"id":7,"user":{"id":99,"name":"author","is_followed":false}}""",
            Novel::class.java,
        )

        assertEquals(99L, novel.user?.id)
        assertEquals("author", novel.user?.name)
        assertEquals(false, novel.user?.is_followed)
    }

    @Test
    fun `缺省字段保留 Kotlin 模型默认值`() {
        val user = gson.fromJson("{\"id\":1}", User::class.java)

        assertEquals(-1L, user.lastTokenTime)
        assertFalse(user.is_login)
        assertNull(user.is_followed)
    }
}
