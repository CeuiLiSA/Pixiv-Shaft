package ceui.lisa.update

import android.app.Application
import ceui.lisa.R
import ceui.pixiv.ui.common.getHumanReadableMessage
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RateLimitTest {

    private fun createResponse(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): Response {
        val builder = Response.Builder()
            .request(
                Request.Builder().url("https://api.github.com/repos/CeuiLiSA/Pixiv-Shaft/releases")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 403) "Forbidden" else if (code == 429) "Too Many Requests" else "OK")
            .body(bodyString.toResponseBody("application/json".toMediaTypeOrNull()))

        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun createHttpException(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): HttpException {
        val rawResponse = createResponse(code, headers, bodyString)
        val retrofitResponse = retrofit2.Response.error<Any>(
            bodyString.toResponseBody("application/json".toMediaTypeOrNull()),
            rawResponse
        )
        return HttpException(retrofitResponse)
    }

    @Test
    fun `rate limit exception type and default message`() {
        val ex = RateLimitException()
        assertEquals("被速率限制，请稍后再试", ex.message)
        assertTrue(AppUpdateChecker.isRateLimit(ex))
    }

    @Test
    fun `identify rate limit from response`() {
        // 429 is always rate limited
        assertTrue(AppUpdateChecker.isRateLimitResponse(createResponse(429)))

        // 403 with x-ratelimit-remaining: 0
        assertTrue(
            AppUpdateChecker.isRateLimitResponse(
                createResponse(403, mapOf("x-ratelimit-remaining" to "0"))
            )
        )

        // 403 with retry-after header
        assertTrue(
            AppUpdateChecker.isRateLimitResponse(
                createResponse(403, mapOf("retry-after" to "60"))
            )
        )

        // 403 with body containing rate limit
        assertTrue(
            AppUpdateChecker.isRateLimitResponse(
                createResponse(403, emptyMap(), "{\"message\":\"API rate limit exceeded\"}")
            )
        )

        // 403 but remaining > 0 and no rate limit mention
        assertFalse(
            AppUpdateChecker.isRateLimitResponse(
                createResponse(
                    403,
                    mapOf("x-ratelimit-remaining" to "50"),
                    "{\"message\":\"Must authenticate\"}"
                )
            )
        )

        // 200 OK
        assertFalse(AppUpdateChecker.isRateLimitResponse(createResponse(200)))
    }

    @Test
    fun `identify rate limit from throwable`() {
        // RateLimitException
        assertTrue(AppUpdateChecker.isRateLimit(RateLimitException()))

        // 429 HttpException
        assertTrue(AppUpdateChecker.isRateLimit(createHttpException(429)))

        // 403 with remaining: 0
        assertTrue(
            AppUpdateChecker.isRateLimit(
                createHttpException(403, mapOf("x-ratelimit-remaining" to "0"))
            )
        )

        // 403 with retry-after
        assertTrue(
            AppUpdateChecker.isRateLimit(
                createHttpException(403, mapOf("retry-after" to "120"))
            )
        )

        // Nested cause
        val wrapped = RuntimeException("outer", RateLimitException())
        assertTrue(AppUpdateChecker.isRateLimit(wrapped))

        // Generic IOException not rate limit
        assertFalse(AppUpdateChecker.isRateLimit(IOException("connection reset")))

        // 404 Not Found
        assertFalse(AppUpdateChecker.isRateLimit(createHttpException(404)))
    }

    @Test
    fun `human readable message maps rate limit correctly`() {
        val context = RuntimeEnvironment.getApplication()
        val expected = context.getString(R.string.update_rate_limited)

        // RateLimitException directly
        assertEquals(expected, RateLimitException().getHumanReadableMessage(context))

        // 全 app 共用这个映射：别的服务（pixiv / pixshaft-api）的 429 必须照旧透传服务端文案，
        // 不能被 GitHub 的限流提示顶掉。GitHub 的限流在 AppUpdateChecker 里已统一转成 RateLimitException。
        val serverMsg = "额度已用完"
        val otherHttpEx = createHttpException(
            429,
            bodyString = """{"error":{"user_message":"$serverMsg","message":"Rate Limit"}}"""
        )
        val otherMessage = otherHttpEx.getHumanReadableMessage(context)
        assertTrue(otherMessage != expected)
        assertTrue(otherMessage.contains(serverMsg))
    }

    @Test
    @Config(qualifiers = "zh")
    fun `rate limited string in Chinese is accurate`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("被速率限制，请稍后再试", context.getString(R.string.update_rate_limited))
        assertEquals(
            "被速率限制，请于 20:15 后再试",
            context.getString(R.string.update_rate_limited_with_reset, "20:15")
        )
    }

    @Test
    fun `extract reset epoch seconds from exception and http response`() {
        // From RateLimitException
        val exWithReset = RateLimitException(resetEpochSeconds = 1728400000L)
        assertEquals(1728400000L, AppUpdateChecker.extractResetEpochSeconds(exWithReset))

        // From HttpException
        val httpEx = createHttpException(403, mapOf("x-ratelimit-reset" to "1728400123"))
        assertEquals(1728400123L, AppUpdateChecker.extractResetEpochSeconds(httpEx))

        // From nested cause
        val wrapped = RuntimeException("outer", httpEx)
        assertEquals(1728400123L, AppUpdateChecker.extractResetEpochSeconds(wrapped))

        // None when header absent
        val httpExNoReset = createHttpException(403, mapOf("x-ratelimit-remaining" to "0"))
        org.junit.Assert.assertNull(AppUpdateChecker.extractResetEpochSeconds(httpExNoReset))
    }

    @Test
    fun `format reset time correctly for same day and cross day`() {
        // Base: 2026-10-08 19:30:00 UTC (1791487800L)
        // Same day: 2026-10-08 20:15:00
        val nowCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 8, 19, 30, 0)
        }
        val resetSameDay = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 8, 20, 15, 0)
        }
        val formattedSameDay = AppUpdateChecker.formatResetTime(
            resetSameDay.timeInMillis / 1000L,
            nowCal.timeInMillis
        )
        assertEquals("20:15", formattedSameDay)

        // Cross day: 2026-10-09 00:15:00
        val resetNextDay = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 9, 0, 15, 0)
        }
        val formattedNextDay = AppUpdateChecker.formatResetTime(
            resetNextDay.timeInMillis / 1000L,
            nowCal.timeInMillis
        )
        val expectedFormat =
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(resetNextDay.time)
        assertEquals(expectedFormat, formattedNextDay)
    }

    @Test
    @Config(qualifiers = "zh")
    fun `getRateLimitMessage presents reset time when available and in future`() {
        val context = RuntimeEnvironment.getApplication()
        val now = 1000000000_000L
        val resetInFuture = (now / 1000L) + 1800L // 30 minutes later

        val msgWithFuture = AppUpdateChecker.getRateLimitMessage(context, resetInFuture, now)
        val expectedTime = AppUpdateChecker.formatResetTime(resetInFuture, now)
        assertEquals("被速率限制，请于 $expectedTime 后再试", msgWithFuture)

        // Reset in the past falls back to "请稍后再试"
        val resetInPast = (now / 1000L) - 10L
        val msgWithPast = AppUpdateChecker.getRateLimitMessage(context, resetInPast, now)
        assertEquals("被速率限制，请稍后再试", msgWithPast)

        // No reset header falls back to "请稍后再试"
        val msgNoReset = AppUpdateChecker.getRateLimitMessage(context, null, now)
        assertEquals("被速率限制，请稍后再试", msgNoReset)
    }

    @Test
    fun `version history source propagates rate limit exception`() = runBlocking {
        val source = VersionHistorySource(
            currentVersion = "4.9.1",
            fromStore = false,
            fetch = { throw RateLimitException() }
        )
        try {
            source.load(null)
            fail("Expected RateLimitException to be thrown")
        } catch (e: RateLimitException) {
            assertEquals("被速率限制，请稍后再试", e.message)
        }
    }
}
