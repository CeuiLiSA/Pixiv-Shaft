package ceui.pixiv.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import ceui.lisa.utils.BackupUtils
import ceui.pixiv.db.HistoryBackfill
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object DeviceTransfer {
    private const val PAIRING_TIMEOUT = 120_000
    private const val IO_TIMEOUT = 30_000
    private const val COMPLETE_TIMEOUT = 15 * 60_000L
    private const val READY = 1
    private const val APPROVED = 2
    private const val RESTORED = 3
    private const val ACCOUNT_MISMATCH = 4
    private const val RESTORE_FAILED = 5
    private const val ACCEPTED = 6
    private const val APPLY_BACKUP = 7
    private const val BUFFER_SIZE = 64 * 1024

    enum class Error { WIFI, ACCOUNT, EXPIRED, CONNECTION, RESTORE, STATUS_UNKNOWN }
    class Failure(val error: Error) : IOException()

    suspend fun send(
        context: Context,
        uid: Long,
        onPair: (TransferCode) -> Unit,
        awaitApproval: suspend (String) -> Unit,
        onPreparing: () -> Unit,
        onProgress: (Int) -> Unit,
        onRemoteRestore: suspend () -> Unit,
    ) = withSockets { sockets ->
        checkAccount(uid)
        val (peer, input, output) = sockets.withDeadline(COMPLETE_TIMEOUT) {
            val connection = pair(context, uid, sockets, onPair)
            val (peer, input, output) = connection
            val comparison = SecureRandom().nextInt(1_000_000)
            output.writeByte(READY)
            output.writeInt(comparison)
            output.flush()
            confirm(peer, input, output, uid, sockets) { awaitApproval(formatComparison(comparison)) }
            onPreparing()

            withPayload(context) { payload, writer ->
                GZIPOutputStream(writer, BUFFER_SIZE).use { gzip ->
                    val job = currentCoroutineContext()
                    BackupUtils.writeTransferBackup(context, gzip) { job.ensureActive() }
                }
                checkAccount(uid)
                val size = payload.channel.size()
                output.writeLong(size)
                copyPayload(payload, output, size, onProgress)
                output.flush()
            }

            connection
        }

        // Once the receiver is told to apply the backup, the sender cannot undo the import.
        onRemoteRestore()
        checkAccount(uid)
        val result = try {
            sockets.withDeadline(COMPLETE_TIMEOUT) {
                output.writeByte(APPLY_BACKUP)
                output.flush()
                peer.soTimeout = COMPLETE_TIMEOUT.toInt()
                input.readUnsignedByte()
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw Failure(Error.STATUS_UNKNOWN)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw Failure(Error.STATUS_UNKNOWN)
        }
        when (result) {
            RESTORED -> Unit
            RESTORE_FAILED -> throw Failure(Error.RESTORE)
            else -> throw Failure(Error.STATUS_UNKNOWN)
        }
    }

    private data class Peer(
        val socket: SSLSocket,
        val input: DataInputStream,
        val output: DataOutputStream,
    )

    private suspend fun pair(
        context: Context,
        uid: Long,
        sockets: Sockets,
        onPair: (TransferCode) -> Unit,
    ): Peer {
        val address = wifi(context).firstOrNull()?.address ?: throw Failure(Error.WIFI)
        val certificate = HeldCertificate.Builder().commonName("Shaft transfer").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext()
        val server = sockets.add(tls.serverSocketFactory.createServerSocket() as SSLServerSocket)
        server.enabledProtocols = server.supportedProtocols.filter(::isModernTls).toTypedArray()
        server.bind(InetSocketAddress(address, 0))
        val nonce = ByteArray(TransferCode.NONCE_SIZE).apply { SecureRandom().nextBytes(this) }
        onPair(TransferCode(address, server.localPort, certificate.certificate.transferFingerprint(), nonce.toByteString()))

        val deadline = SystemClock.elapsedRealtime() + PAIRING_TIMEOUT
        while (true) {
            currentCoroutineContext().ensureActive()
            checkAccount(uid)
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) throw Failure(Error.EXPIRED)
            server.soTimeout = remaining.toInt()
            val peer = try {
                sockets.add(server.accept() as SSLSocket)
            } catch (_: SocketTimeoutException) {
                throw Failure(Error.EXPIRED)
            }
            var accountLost = false
            val paired = try {
                peer.soTimeout = minOf(IO_TIMEOUT, remaining.toInt(), 5_000)
                peer.startHandshake()
                val input = DataInputStream(peer.inputStream.buffered())
                val output = DataOutputStream(peer.outputStream.buffered())
                val claim = ByteArray(nonce.size).also(input::readFully)
                if (!MessageDigest.isEqual(claim, nonce)) {
                    null
                } else {
                    val peerUid = input.readLong()
                    if (!hasAccount(uid)) {
                        accountLost = true
                        output.writeByte(ACCOUNT_MISMATCH)
                        output.flush()
                        null
                    } else if (peerUid != uid) {
                        output.writeByte(ACCOUNT_MISMATCH)
                        output.flush()
                        null
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        null
                    } else {
                        Peer(peer, input, output)
                    }
                }
            } catch (_: IOException) {
                null
            }
            if (paired == null) {
                sockets.release(peer)
                if (accountLost) throw Failure(Error.ACCOUNT)
                continue
            }

            // Stop accepting connections as soon as the one-time code has been claimed.
            sockets.release(server)
            return paired
        }
    }

    private suspend fun confirm(
        peer: SSLSocket,
        input: DataInputStream,
        output: DataOutputStream,
        uid: Long,
        sockets: Sockets,
        awaitApproval: suspend () -> Unit,
    ) = sockets.withDeadline(PAIRING_TIMEOUT.toLong()) {
        coroutineScope {
            peer.soTimeout = PAIRING_TIMEOUT + IO_TIMEOUT
            // A receiver leaving the waiting screen closes the socket and ends approval immediately.
            val acceptance = async {
                if (input.readUnsignedByte() != ACCEPTED) throw Failure(Error.CONNECTION)
            }
            try {
                awaitApproval()
                checkAccount(uid)
                output.writeByte(APPROVED)
                output.flush()
                acceptance.await()
            } catch (error: Exception) {
                sockets.release(peer)
                throw error
            }
        }
    }

    suspend fun receive(
        context: Context,
        uid: Long,
        code: TransferCode,
        onWaiting: (String) -> Unit,
        onPreparing: () -> Unit,
        onProgress: (Int) -> Unit,
        onRestoring: suspend () -> Unit,
    ) = withSockets { sockets ->
        checkAccount(uid)
        withPayload(context) { payload, writer ->
            val output = sockets.withDeadline(COMPLETE_TIMEOUT) {
                val (peer, input, output) = connect(context, uid, code, sockets, onWaiting)
                onPreparing()
                peer.soTimeout = COMPLETE_TIMEOUT.toInt()
                receivePayload(input, writer, onProgress)
                writer.close()
                output
            }
            onRestoring()
            val job = currentCoroutineContext().job
            try {
                BackupUtils.restoreTransferBackup(context, payload, uid) {
                    job.ensureActive()
                    checkAccount(uid)
                }
            } catch (error: Exception) {
                reportRestoreResult(output, RESTORE_FAILED)
                throw error
            }
            reportRestoreResult(output, RESTORED)
            HistoryBackfill.maybeSchedule()
        }
    }

    private fun connect(
        context: Context,
        uid: Long,
        code: TransferCode,
        sockets: Sockets,
        onWaiting: (String) -> Unit,
    ): Peer {
        val wifi = wifi(context).firstOrNull { it.reaches(code.address) } ?: throw Failure(Error.WIFI)
        val raw = sockets.add(wifi.network.socketFactory.createSocket())
        raw.connect(InetSocketAddress(code.address, code.port), 7_000)
        val tls = pinnedTls(code.fingerprint)
        val peer = sockets.add(tls.socketFactory.createSocket(raw, code.address.hostAddress, code.port, true) as SSLSocket)
        peer.enabledProtocols = peer.supportedProtocols.filter(::isModernTls).toTypedArray()
        peer.soTimeout = IO_TIMEOUT
        peer.startHandshake()
        val input = DataInputStream(peer.inputStream.buffered())
        val output = DataOutputStream(peer.outputStream.buffered())
        checkAccount(uid)
        output.write(code.nonce.toByteArray())
        output.writeLong(uid)
        output.flush()
        when (input.readUnsignedByte()) {
            READY -> {
                val comparison = input.readInt()
                if (comparison !in 0..999_999) throw Failure(Error.CONNECTION)
                onWaiting(formatComparison(comparison))
            }
            ACCOUNT_MISMATCH -> throw Failure(Error.ACCOUNT)
            else -> throw Failure(Error.CONNECTION)
        }
        peer.soTimeout = PAIRING_TIMEOUT + IO_TIMEOUT
        if (input.readUnsignedByte() != APPROVED) throw Failure(Error.CONNECTION)
        checkAccount(uid)
        output.writeByte(ACCEPTED)
        output.flush()
        return Peer(peer, input, output)
    }

    internal suspend fun receivePayload(input: DataInputStream, writer: OutputStream, onProgress: (Int) -> Unit) {
        val size = input.readLong()
        if (size <= 0) throw Failure(Error.CONNECTION)
        copyPayload(input, writer, size, onProgress)
        if (input.read() != APPLY_BACKUP) throw Failure(Error.CONNECTION)
    }

    private fun reportRestoreResult(output: DataOutputStream, result: Int) {
        // The local import is authoritative even if the other phone disconnects before the ACK.
        try {
            output.writeByte(result)
            output.flush()
        } catch (_: IOException) {
            // The sender reports that it could not confirm the outcome.
        }
    }

    private fun isModernTls(protocol: String): Boolean = protocol == "TLSv1.2" || protocol == "TLSv1.3"

    private fun formatComparison(value: Int): String = String.format(Locale.ROOT, "%06d", value)

    private fun checkAccount(uid: Long) {
        if (!hasAccount(uid)) throw Failure(Error.ACCOUNT)
    }

    private fun hasAccount(uid: Long) =
        uid > 0 && SessionManager.isLoggedIn && SessionManager.loggedInUid == uid

    private fun pinnedTls(fingerprint: ByteString): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException()
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.singleOrNull() ?: throw CertificateException()
                if (!MessageDigest.isEqual(certificate.transferFingerprint().toByteArray(), fingerprint.toByteArray())) {
                    throw CertificateException()
                }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    }

    private data class WifiLink(val network: Network, val address: Inet4Address, val prefix: Int) {
        fun reaches(peer: Inet4Address): Boolean {
            if (address == peer || prefix !in 1..31) return false
            val fullBytes = prefix / 8
            val remainder = prefix % 8
            val local = address.address
            val remote = peer.address
            return (0 until fullBytes).all { local[it] == remote[it] } &&
                (remainder == 0 || (local[fullBytes].toInt() xor remote[fullBytes].toInt()) and
                    (0xff shl (8 - remainder)) == 0)
        }
    }

    internal data class WifiCandidate<T>(
        val link: T,
        val address: Inet4Address,
        val prefix: Int,
        val hasInternet: Boolean,
        val validated: Boolean,
    )

    internal fun <T> selectWifiLinks(candidates: List<WifiCandidate<T>>): List<T> =
        candidates
            .filter { it.address.isUsableTransferAddress() && it.prefix in 1..31 }
            .sortedWith(
                compareByDescending<WifiCandidate<T>> { !it.address.isLinkLocalAddress }
                    .thenByDescending { it.validated }
                    .thenByDescending { it.hasInternet },
            )
            .map { it.link }

    @Suppress("DEPRECATION") // Enumerate Wi-Fi even if a VPN or cellular network owns the default route.
    private fun wifi(context: Context): List<WifiLink> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val candidates = manager.allNetworks.flatMap { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) return@flatMap emptyList()
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                (link.address as? Inet4Address)?.let { address ->
                    WifiCandidate(
                        WifiLink(network, address, link.prefixLength),
                        address,
                        link.prefixLength,
                        hasInternet,
                        validated,
                    )
                }
            }
        }
        return selectWifiLinks(candidates)
    }

    private suspend fun copyPayload(
        source: InputStream,
        target: OutputStream,
        size: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var progress = 0
        onProgress(0)
        while (copied < size) {
            currentCoroutineContext().ensureActive()
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
            if (read < 0) throw Failure(Error.CONNECTION)
            target.write(buffer, 0, read)
            copied += read
            val next = (copied.toDouble() / size * 100).toInt()
            if (next != progress) {
                progress = next
                onProgress(progress)
            }
        }
    }

    /**
     * Open both descriptors, then unlink the empty file before writing any data.
     * Android still lets both descriptors use it and reclaims the bytes on close or process death.
     */
    private suspend fun <T> withPayload(
        context: Context,
        action: suspend (FileInputStream, FileOutputStream) -> T,
    ): T {
        val file = File.createTempFile("shaft-transfer-", null, context.cacheDir)
        try {
            return FileInputStream(file).use { reader ->
                FileOutputStream(file).use { writer ->
                    if (!file.delete()) throw IOException("Cannot unlink transfer staging file")
                    action(reader, writer)
                }
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun <T> withSockets(block: suspend (Sockets) -> T): T =
        withContext(Dispatchers.IO) {
            val sockets = Sockets()
            try {
                sockets.closeOnCancellation { block(sockets) }
            } finally {
                sockets.close()
            }
        }

    private suspend fun <T> Sockets.withDeadline(timeout: Long, block: suspend () -> T): T =
        withTimeout(timeout) { closeOnCancellation(block) }

    private suspend fun <T> Sockets.closeOnCancellation(block: suspend () -> T): T = coroutineScope {
        val parent = coroutineContext.job
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!parent.isActive) close()
            }
        }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }

    private class Sockets : Closeable {
        private val open = mutableListOf<Closeable>()
        private var closed = false

        @Synchronized fun <T : Closeable> add(socket: T): T {
            if (closed) {
                socket.close()
                throw IOException("Transfer closed")
            }
            open += socket
            return socket
        }

        @Synchronized fun release(socket: Closeable) {
            open.remove(socket)
            runCatching { socket.close() }
        }

        @Synchronized override fun close() {
            closed = true
            // Close the underlying client TCP socket before its TLS wrapper.
            open.forEach { runCatching { it.close() } }
            open.clear()
        }
    }
}
