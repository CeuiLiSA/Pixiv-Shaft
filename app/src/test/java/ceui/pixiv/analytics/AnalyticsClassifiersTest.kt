package ceui.pixiv.analytics

import ceui.pixiv.login.PixivOAuthResult
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AnalyticsClassifiersTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `illust type maps to content type`() {
        assertEquals(Analytics.ContentType.ILLUST, AnalyticsClassifiers.contentTypeOf("illust"))
        assertEquals(Analytics.ContentType.MANGA, AnalyticsClassifiers.contentTypeOf("manga"))
        assertEquals(Analytics.ContentType.UGOIRA, AnalyticsClassifiers.contentTypeOf("ugoira"))
        assertEquals(Analytics.ContentType.ILLUST, AnalyticsClassifiers.contentTypeOf(null))
        assertEquals(Analytics.ContentType.ILLUST, AnalyticsClassifiers.contentTypeOf("something_new"))
    }

    @Test
    fun `http status wins anywhere in the cause chain`() {
        assertEquals("http_404", AnalyticsClassifiers.failureType(http(404)))
        assertEquals("http_429", AnalyticsClassifiers.failureType(IOException("wrapped", http(429))))
    }

    @Test
    fun `json decoding failures share one bucket`() {
        assertEquals("decode_json", AnalyticsClassifiers.failureType(JsonSyntaxException("bad")))
        assertEquals("decode_json", AnalyticsClassifiers.failureType(MalformedJsonException("bad")))
    }

    @Test
    fun `transport failures reuse the nana7mi vocabulary`() {
        assertEquals("network_timeout", AnalyticsClassifiers.failureType(SocketTimeoutException()))
        assertEquals(
            "network_dns",
            AnalyticsClassifiers.failureType(RuntimeException(UnknownHostException("i.pximg.net"))),
        )
        assertEquals("network_connect", AnalyticsClassifiers.failureType(ConnectException()))
        assertEquals("network_io", AnalyticsClassifiers.failureType(IOException("reset")))
    }

    @Test
    fun `anything else is unexpected`() {
        assertEquals(AnalyticsClassifiers.UNEXPECTED, AnalyticsClassifiers.failureType(IllegalStateException()))
    }

    @Test
    fun `self referencing cause does not loop`() {
        val loop = object : RuntimeException() {
            override val cause: Throwable get() = this
        }
        assertEquals(AnalyticsClassifiers.UNEXPECTED, AnalyticsClassifiers.failureType(loop))
    }

    @Test
    fun `download failures check storage before network`() {
        assertEquals(
            "storage_full",
            AnalyticsClassifiers.downloadFailureReason(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            "storage_permission",
            AnalyticsClassifiers.downloadFailureReason(IOException("open", SecurityException("denied"))),
        )
        assertEquals("http_403", AnalyticsClassifiers.downloadFailureReason(IOException("HTTP 403")))
        assertEquals("http_404", AnalyticsClassifiers.downloadFailureReason(IOException("HTTP 404 Not Found")))
        // 只认三位状态码前缀，别的数字串不能变成新的维度值。
        assertEquals("network_io", AnalyticsClassifiers.downloadFailureReason(IOException("HTTP 40312")))
        assertEquals("network_timeout", AnalyticsClassifiers.downloadFailureReason(SocketTimeoutException()))
        assertEquals(AnalyticsClassifiers.UNEXPECTED, AnalyticsClassifiers.downloadFailureReason(null))
    }

    @Test
    fun `login failures map by result subtype`() {
        assertEquals(
            "network_timeout",
            AnalyticsClassifiers.loginFailureReason(
                PixivOAuthResult.Failure.NetworkError("timeout", SocketTimeoutException()),
            ),
        )
        assertEquals(
            "network_io",
            AnalyticsClassifiers.loginFailureReason(PixivOAuthResult.Failure.NetworkError("offline", null)),
        )
        assertEquals(
            "http_400",
            AnalyticsClassifiers.loginFailureReason(
                PixivOAuthResult.Failure.ServerRejected(400, "invalid_grant", null),
            ),
        )
        assertEquals(
            "missing_code",
            AnalyticsClassifiers.loginFailureReason(PixivOAuthResult.Failure.MissingCode("no code", null)),
        )
        assertEquals(
            "missing_verifier",
            AnalyticsClassifiers.loginFailureReason(PixivOAuthResult.Failure.MissingVerifier("no verifier", null)),
        )
    }
}
