package ceui.lisa.http

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFailureClassifierTest {

    @Test
    fun `specific cause wins through an IOException wrapper`() {
        assertEquals(
            TransportFailureKind.DNS,
            classifyTransportFailure(IOException("outer", UnknownHostException("dns"))),
        )
        assertEquals(
            TransportFailureKind.TIMEOUT,
            classifyTransportFailure(IOException("outer", SocketTimeoutException("slow"))),
        )
        assertEquals(
            TransportFailureKind.CONNECT,
            classifyTransportFailure(IOException("outer", ConnectException("refused"))),
        )
    }

    @Test
    fun `cancellation and tls are stable and not retryable`() {
        val cancelled = classifyTransportFailure(CancellationException("gone"))
        val tls = classifyTransportFailure(SSLHandshakeException("certificate"))

        assertEquals("network_cancelled", cancelled?.wire)
        assertFalse(cancelled?.retryable ?: true)
        assertEquals("network_tls", tls?.wire)
        assertFalse(tls?.retryable ?: true)
    }

    @Test
    fun `plain IO stays retryable and programming errors stay outside transport`() {
        val io = classifyTransportFailure(IOException("reset"))

        assertEquals("network_io", io?.wire)
        assertTrue(io?.retryable == true)
        assertNull(classifyTransportFailure(IllegalStateException("bug")))
    }
}
