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

    /**
     * 读超时两种 message 都要认（okio watchdog 的 "timeout" / socket soTimeout 的 "Read timed out"，
     * 谁先到点由谁抛）；连接超时（Android "failed to connect to … after Nms" / JVM
     * "Connect timed out"）必须排除在外 —— 它不受「图片加载/下载断流阈值」影响，
     * 不该被当成「用户调小了阈值」的预期内事件去静默重连。
     */
    @Test
    fun `read timeout is told apart from connect timeout`() {
        assertTrue(isReadTimeoutFailure(SocketTimeoutException("timeout")))
        assertTrue(isReadTimeoutFailure(SocketTimeoutException("Read timed out")))
        assertTrue(isReadTimeoutFailure(IOException("outer", SocketTimeoutException("timeout"))))
        assertFalse(
            isReadTimeoutFailure(SocketTimeoutException("failed to connect to x after 10000ms"))
        )
        assertFalse(isReadTimeoutFailure(SocketTimeoutException("Connect timed out")))
        assertFalse(isReadTimeoutFailure(ConnectException("refused")))
        assertFalse(isReadTimeoutFailure(IOException("reset")))
    }
}
