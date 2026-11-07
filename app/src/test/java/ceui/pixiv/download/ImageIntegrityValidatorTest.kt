package ceui.pixiv.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater

class ImageIntegrityValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createValidPng(dataSize: Int = 1024): File {
        val file = tempFolder.newFile("valid.png")
        RandomAccessFile(file, "rw").use { raf ->
            // PNG signature
            raf.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            // IHDR: 13 bytes data: 10x10, 8-bit RGBA
            val ihdrData = ByteArray(13).apply {
                this[3] = 10 // width = 10
                this[7] = 10 // height = 10
                this[8] = 8  // bit depth = 8
                this[9] = 6  // color type = 6 (RGBA)
            }
            writeChunk(raf, "IHDR", ihdrData)

            // IDAT: compress raw dummy scanlines (10 scanlines * (1 + 10 * 4))
            val raw = ByteArray(10 * (1 + 10 * 4)) { 0 }
            val compressed = ByteArray(dataSize)
            val deflater = Deflater()
            deflater.setInput(raw)
            deflater.finish()
            val compLen = deflater.deflate(compressed)
            deflater.end()

            val idatData = compressed.copyOf(compLen)
            writeChunk(raf, "IDAT", idatData)

            // IEND
            writeChunk(raf, "IEND", ByteArray(0))
        }
        return file
    }

    private fun writeChunk(raf: RandomAccessFile, type: String, data: ByteArray) {
        val len = data.size
        raf.writeInt(len)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        raf.write(typeBytes)
        raf.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        raf.writeInt(crc.value.toInt())
    }

    @Test
    fun `validate - healthy png passes without repair`() {
        val file = createValidPng()
        val result = ImageIntegrityValidator.validateAndRepair(file)
        assertTrue(result.isValid)
        assertFalse(result.repaired)
    }

    @Test
    fun `validateAndRepair - single-bit flip in PNG IDAT chunk is self-healed in-place`() {
        val file = createValidPng()

        // 找到 IDAT chunk 内部位置翻转 1 个 bit
        RandomAccessFile(file, "rw").use { raf ->
            // 跳过 PNG header (8) + IHDR (12 + 13) = 33
            // IDAT chunk data starts at 33 + 8 = 41
            raf.seek(45)
            val b = raf.read()
            raf.seek(45)
            raf.write(b xor 0x80) // 翻转第 7 位
        }

        val result = ImageIntegrityValidator.validateAndRepair(file)
        assertTrue("单比特翻转应成功自愈", result.isValid)
        assertTrue("自愈状态应为 true", result.repaired)
        assertTrue("原因应包含自愈信息", result.reason.contains("自愈单比特翻转"))

        // 再次校验已修复的文件，应为原生 OK
        val secondResult = ImageIntegrityValidator.validateAndRepair(file)
        assertTrue(secondResult.isValid)
        assertFalse("再次校验无需二次自愈", secondResult.repaired)
    }

    @Test
    fun `validateAndRepair - multi-bit corruption is rejected`() {
        val file = createValidPng()

        // 翻转同一 chunk 内部两个不同字节的 bit
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(42)
            val b1 = raf.read()
            raf.seek(42)
            raf.write(b1 xor 0x01)

            raf.seek(48)
            val b2 = raf.read()
            raf.seek(48)
            raf.write(b2 xor 0x02)
        }

        val result = ImageIntegrityValidator.validateAndRepair(file)
        assertFalse("多比特错误不应被自愈", result.isValid)
        assertFalse(result.repaired)
        assertTrue("应包含 CRC 校验失败信息", result.reason.contains("CRC 校验失败"))
    }

    @Test
    fun `findSingleBitFlip - accurate byte and bit localization`() {
        val rawChunk =
            "IDAT".toByteArray(Charsets.US_ASCII) + ByteArray(1000) { (it % 256).toByte() }
        val crc = CRC32().apply { update(rawChunk) }
        val trueCrc = crc.value.toInt()

        val corrupted = rawChunk.clone()
        val targetByte = 450
        val targetBit = 5
        corrupted[targetByte] = (corrupted[targetByte].toInt() xor (1 shl targetBit)).toByte()

        val badCrc = CRC32().apply { update(corrupted) }.value.toInt()

        val location = ImageIntegrityValidator.findSingleBitFlip(rawChunk.size, badCrc, trueCrc)
        assertNotNull(location)
        assertEquals(targetByte, location!!.first)
        assertEquals(targetBit, location.second)
    }

    @Test
    fun `validateJpeg - valid and truncated`() {
        val validJpeg = tempFolder.newFile("test.jpg").apply {
            writeBytes(ByteArray(200) { 0 }.apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xD8.toByte()
                this[2] = 0xFF.toByte()
                this[198] = 0xFF.toByte()
                this[199] = 0xD9.toByte() // EOI
            })
        }
        assertTrue(ImageIntegrityValidator.validateAndRepair(validJpeg).isValid)

        val truncatedJpeg = tempFolder.newFile("truncated.jpg").apply {
            writeBytes(ByteArray(200) { 0 }.apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xD8.toByte()
                this[2] = 0xFF.toByte()
                // 缺少 0xFF, 0xD9
            })
        }
        val badResult = ImageIntegrityValidator.validateAndRepair(truncatedJpeg)
        assertFalse(badResult.isValid)
        assertTrue(badResult.reason.contains("EOI"))
    }

    @Test
    fun `validateGif - valid and truncated`() {
        val validGif = tempFolder.newFile("test.gif").apply {
            writeBytes(ByteArray(50) { 0 }.apply {
                "GIF89a".toByteArray().copyInto(this)
                this[49] = 0x3B // ';' trailer
            })
        }
        assertTrue(ImageIntegrityValidator.validateAndRepair(validGif).isValid)

        val truncatedGif = tempFolder.newFile("truncated.gif").apply {
            writeBytes(ByteArray(50) { 0 }.apply {
                "GIF89a".toByteArray().copyInto(this)
                this[49] = 0x00
            })
        }
        assertFalse(ImageIntegrityValidator.validateAndRepair(truncatedGif).isValid)
    }
}
