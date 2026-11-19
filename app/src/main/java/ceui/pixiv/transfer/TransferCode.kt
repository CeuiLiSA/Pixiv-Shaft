package ceui.pixiv.transfer

import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.net.Inet4Address
import java.net.InetAddress

/** The QR code is the out-of-band channel for the ephemeral TLS pin and one-time nonce. */
internal class TransferCode(
    val address: Inet4Address,
    val port: Int,
    val fingerprint: ByteString,
    val nonce: ByteString,
) {
    fun encode(): String =
        "$PROTOCOL/${address.hostAddress}/$port/${fingerprint.base64Url()}/${nonce.base64Url()}"

    companion object {
        fun decode(value: String): TransferCode? {
            if (value.length > 160) return null
            val parts = value.split('/')
            if (parts.size != 5 || parts[0] != PROTOCOL) return null
            val ip = parts[1].split('.')
            if (ip.size != 4) return null
            val bytes = ByteArray(4)
            for ((index, octet) in ip.withIndex()) {
                val number = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                if (octet != number.toString()) return null
                bytes[index] = number.toByte()
            }
            val address = InetAddress.getByAddress(bytes) as Inet4Address
            if (!address.isUsableTransferAddress()) return null
            val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = parts[3].decodeBase64()?.takeIf { it.size == 32 } ?: return null
            val nonce = parts[4].decodeBase64()?.takeIf { it.size == NONCE_SIZE } ?: return null
            return TransferCode(address, port, fingerprint, nonce)
        }

        private const val PROTOCOL = "shaft-transfer:2"
        internal const val NONCE_SIZE = 16
    }
}

internal fun Inet4Address.isUsableTransferAddress(): Boolean =
    !isLoopbackAddress && !isAnyLocalAddress && !isMulticastAddress

internal fun java.security.cert.Certificate.transferFingerprint(): ByteString =
    encoded.toByteString().sha256()
