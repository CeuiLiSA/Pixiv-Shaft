package ceui.pixiv.shaftapi

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Transport timing contains hosts and durations only; signatures stay out of automatic events. */
internal class MediaNetworkTiming(private val trace: MediaUploadTrace) : EventListener() {
    private var dnsAt = 0L
    private var connectAt = 0L
    private var tlsAt = 0L
    private var bodyAt = 0L
    private var headersAt = 0L
    private var connected = false
    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    override fun dnsStart(call: Call, domainName: String) { dnsAt = System.nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        trace.event("dns", "host=$domainName dnsMs=${ms(dnsAt)} addressCount=${inetAddressList.size}")
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connected = true
        connectAt = System.nanoTime()
    }
    override fun secureConnectStart(call: Call) { tlsAt = System.nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace.event("tls", "tlsMs=${ms(tlsAt)}")
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        trace.event("connected", "connectMs=${ms(connectAt)} protocol=$protocol")
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
        trace.event("connection", "host=${call.request().url.host} reused=${!connected} protocol=${connection.protocol()}")
    }
    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { headersAt = System.nanoTime() }
    override fun requestBodyStart(call: Call) { bodyAt = System.nanoTime() }
    override fun requestBodyEnd(call: Call, byteCount: Long) {
        trace.event("request_body_sent", "bytes=$byteCount networkBodyMs=${ms(bodyAt)}")
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        trace.event("response_headers", "http=${response.code} headersToResponseMs=${ms(headersAt)}")
        response.header("Server-Timing")?.let { trace.event("server_timing", it) }
    }
    companion object {
        val factory = Factory { call ->
            call.request().tag(MediaUploadTrace::class.java)?.let(::MediaNetworkTiming) ?: NONE
        }
    }
}
