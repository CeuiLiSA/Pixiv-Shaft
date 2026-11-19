package ceui.pixiv.transfer

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DeviceTransferCodeTest {
    private val code = TransferCode(
        InetAddress.getByAddress(byteArrayOf(10, 0, 1, 7)) as Inet4Address,
        49500,
        ByteArray(32) { it.toByte() }.toByteString(),
        ByteArray(16) { (it * 7).toByte() }.toByteString(),
    )

    @Test
    fun `QR contents round trip without DNS or losing binary secrets`() {
        val decoded = checkNotNull(TransferCode.decode(code.encode()))
        assertEquals(code.address, decoded.address)
        assertEquals(code.port, decoded.port)
        assertEquals(code.fingerprint, decoded.fingerprint)
        assertEquals(code.nonce, decoded.nonce)
    }

    @Test
    fun `invalid protocol and unusable endpoints are rejected`() {
        val value = code.encode()
        for (invalid in listOf(
            value.replace(":2/", ":1/"),
            value.replace("10.0.1.7", "example.com"),
            value.replace("10.0.1.7", "127.0.0.5"),
            value.replace("49500", "65536"),
            value.substringBeforeLast('/') + "/abcd",
        )) {
            assertNull(TransferCode.decode(invalid))
        }
    }

    @Test
    fun `wifi selection filters unusable addresses and prioritizes router LAN links`() {
        fun candidate(
            label: String,
            bytes: ByteArray,
            prefix: Int = 24,
            hasInternet: Boolean = false,
            validated: Boolean = false,
        ) = DeviceTransfer.WifiCandidate(
            link = label,
            address = InetAddress.getByAddress(bytes) as Inet4Address,
            prefix = prefix,
            hasInternet = hasInternet,
            validated = validated,
        )

        val selected = DeviceTransfer.selectWifiLinks(
            listOf(
                candidate("loopback", byteArrayOf(127, 0, 0, 1)),
                candidate("host-only", byteArrayOf(192.toByte(), 168.toByte(), 1, 5), prefix = 32),
                candidate("multicast", byteArrayOf(224.toByte(), 0, 0, 1)),
                candidate("link-local", byteArrayOf(169.toByte(), 254.toByte(), 1, 2), prefix = 16, hasInternet = true, validated = true),
                candidate("offline-lan", byteArrayOf(192.toByte(), 168.toByte(), 49, 1)),
                candidate("router-wifi", byteArrayOf(192.toByte(), 168.toByte(), 1, 8), hasInternet = true, validated = true),
            ),
        )

        assertEquals(listOf("router-wifi", "offline-lan", "link-local"), selected)
    }
}
