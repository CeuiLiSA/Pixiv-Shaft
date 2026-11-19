package ceui.pixiv.transfer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class DeviceTransferPayloadTest {
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `complete backup is accepted after the sender authorizes restore`() = runTest {
        val received = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()

        DeviceTransfer.receivePayload(frame(trailer = 7), received, progress::add)

        assertArrayEquals(bytes, received.toByteArray())
        assertEquals(listOf(0, 100), progress)
    }

    @Test
    fun `a complete backup without restore authorization cannot be applied`() = runTest {
        val received = ByteArrayOutputStream()

        try {
            DeviceTransfer.receivePayload(frame(trailer = null), received) {}
            fail("Expected the receiver to reject a frame without restore authorization")
        } catch (error: DeviceTransfer.Failure) {
            assertEquals(DeviceTransfer.Error.CONNECTION, error.error)
        }

        assertArrayEquals(bytes, received.toByteArray())
    }

    private fun frame(trailer: Int?): DataInputStream {
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use { writer ->
            writer.writeLong(bytes.size.toLong())
            writer.write(bytes)
            trailer?.let(writer::writeByte)
        }
        return DataInputStream(frame.toByteArray().inputStream())
    }
}
