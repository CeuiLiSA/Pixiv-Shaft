package ceui.lisa.repo

import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Nana7miNetworkRetryTest {

    @Test
    fun `idempotent call retries one transient failure`() = runTest {
        var calls = 0

        val value = retryNana7miNetworkCall(stage = "test", retryDelayMs = 0L) {
            if (++calls == 1) throw ConnectException("refused")
            "ok"
        }

        assertEquals("ok", value)
        assertEquals(2, calls)
    }

    @Test
    fun `tls failure is returned without replay`() = runTest {
        var calls = 0
        val expected = SSLHandshakeException("certificate")

        val actual = runCatching {
            retryNana7miNetworkCall(stage = "test", retryDelayMs = 0L) {
                calls++
                throw expected
            }
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(1, calls)
    }

    @Test
    fun `result form retries only when its failure is transient`() = runTest {
        data class Result(val error: Throwable? = null, val value: String? = null)
        var calls = 0

        val result = retryNana7miNetworkResult(
            stage = "dispatch",
            retryDelayMs = 0L,
            failureOf = { it.error },
        ) {
            if (++calls == 1) Result(error = ConnectException("refused"))
            else Result(value = "ok")
        }

        assertEquals("ok", result.value)
        assertEquals(2, calls)
    }

    @Test
    fun `coroutine cancellation is never converted into a retry`() = runTest {
        var calls = 0
        val expected = CancellationException("disposed")

        val actual = runCatching {
            retryNana7miNetworkCall(stage = "test", retryDelayMs = 0L) {
                calls++
                throw expected
            }
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(1, calls)
    }
}
