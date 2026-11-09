package ceui.pixiv.download

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * 图像文件完整性校验与自愈门禁。
 *
 * 针对 PNG、JPEG、GIF、WebP 等下载图片进行完整性检测。
 * 针对 PNG，若因弱网传输、内存位翻转等原因出现单比特翻转（Single-bit flip），
 * 利用 IEEE 802.3 CRC-32 伴随式（Syndrome）在 GF(2) 上的单射特性进行单比特 ECC 自愈。
 *
 * 核心安全准则（只有真的修好才放回盘）：
 * 尝试修复时先在内存/流解压中应用该翻转并复核末尾的 zlib Adler-32 校验和；
 * 只有双重 32 位校验全部 100% 通过（零误纠保障），才真正写回磁盘文件或 content URI，
 * 否则绝不修改原盘数据。
 */
object ImageIntegrityValidator {

    data class IntegrityResult(
        @JvmField val isValid: Boolean,
        @JvmField val repaired: Boolean = false,
        @JvmField val reason: String = "",
    ) {
        companion object {
            @JvmField val OK = IntegrityResult(isValid = true, repaired = false, reason = "OK")
            @JvmStatic fun repaired(reason: String) = IntegrityResult(isValid = true, repaired = true, reason = reason)
            @JvmStatic fun error(reason: String) = IntegrityResult(isValid = false, repaired = false, reason = reason)
        }
    }

    private data class PendingRepair(
        val absOffset: Long,
        val bit: Int,
        val typeStr: String
    )

    private data class ByteRepair(val offset: Long, val original: Byte, val repaired: Byte)

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )

    private const val CRC32_POLY = 0xEDB88320.toInt()

    // 列表和大图页可能同时修复同一份文件；校验与回写必须串行，否则会把已修好的 bit 再翻坏。
    // 固定数量的锁避免按路径永久积累锁对象，不同文件仍可并行校验。
    private val repairLocks = Array(32) { Any() }

    private val CRC32_TABLE = IntArray(256) { i ->
        var c = i
        for (j in 0 until 8) {
            c = if ((c and 1) != 0) (c ushr 1) xor CRC32_POLY else (c ushr 1)
        }
        c
    }

    private fun stepZeroByte(c: Int): Int {
        return CRC32_TABLE[c and 0xFF] xor (c ushr 8)
    }

    /**
     * 校验并尝试自愈图像文件。
     */
    @JvmStatic
    fun validateAndRepair(file: File?): IntegrityResult = repairIfPossible(file)

    /**
     * 对磁盘文件尝试 ECC 修复：只有预验证完全通过（Adler-32 吻合），才写回盘。
     */
    @JvmStatic
    fun repairIfPossible(file: File?): IntegrityResult {
        if (file == null) return IntegrityResult.error("文件不存在或为空")
        val lock = repairLocks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % repairLocks.size]
        return synchronized(lock) { validateFile(file) }
    }

    private fun validateFile(file: File): IntegrityResult {
        if (!file.isFile || file.length() <= 0L) {
            return IntegrityResult.error("文件不存在或为空")
        }

        val len = file.length()
        if (len < 8L) {
            return IntegrityResult.error("文件过短（${len} 字节）")
        }

        val header = ByteArray(12)
        try {
            FileInputStream(file).use { input ->
                val read = input.read(header)
                if (read < 8) return IntegrityResult.error("读取头部失败")
            }
        } catch (e: Exception) {
            return IntegrityResult.error("无法读取文件: ${e.message}")
        }

        return when {
            isPng(header) -> validateAndRepairPng(file)
            isJpeg(header) -> validateJpeg(file)
            isGif(header) -> validateGif(file)
            isWebp(header) -> validateWebp(file)
            else -> IntegrityResult.OK
        }
    }

    /**
     * 显示侧入口：当显示层（Sketch / Glide）加载失败时调用。
     * 支持 file:// 和 content:// URI；只有真正自愈修复成功后才写回原盘/原行。
     *
     * @return true 表示成功自愈并已写回盘，调用方可就地重试加载；false 表示不可修复或原图无问题。
     */
    @JvmStatic
    fun repairUriIfPossible(context: Context?, uri: Uri?): Boolean {
        if (context == null || uri == null) return false
        return try {
            val scheme = uri.scheme
            if (scheme == "file") {
                val path = uri.path ?: return false
                val file = File(path)
                val res = repairIfPossible(file)
                if (res.repaired) {
                    Timber.tag("ImageIntegrity").i("显示侧就地修复本地文件成功: %s", path)
                    true
                } else {
                    false
                }
            } else if (scheme == "content") {
                val cr = context.contentResolver
                val tempFile = File.createTempFile("ecc_heal_", ".tmp", context.cacheDir)
                try {
                    (cr.openInputStream(uri) ?: return false).use { input ->
                        tempFile.outputStream().use { out -> input.copyTo(out) }
                    }

                    val res = repairIfPossible(tempFile)
                    if (res.repaired) {
                        writeContentRepair(context, uri, tempFile)
                    } else {
                        false
                    }
                } finally {
                    tempFile.delete()
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.tag("ImageIntegrity").w(e, "repairUriIfPossible 处理异常: %s", uri)
            false
        }
    }

    private fun writeContentRepair(context: Context, uri: Uri, repairedFile: File): Boolean {
        // 只改已验证的那一个字节。rwt / w 整图重写在断写时会把原图截断，无法安全回退。
        // 云端/管道 provider 不支持随机访问时，校验或定位会失败，保持源文件不动。
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: return false
        return descriptor.use {
            ParcelFileDescriptor.AutoCloseInputStream(it.dup()).use { input ->
                val repair = findByteRepair(input.channel, repairedFile) ?: return false
                val current = ByteBuffer.allocate(1)
                if (input.channel.read(current, repair.offset) != 1 || current.array()[0] != repair.original) {
                    return false
                }
                ParcelFileDescriptor.AutoCloseOutputStream(it.dup()).use { output ->
                    val written = output.channel.write(ByteBuffer.wrap(byteArrayOf(repair.repaired)), repair.offset)
                    if (written != 1) return false
                }
                Timber.tag("ImageIntegrity").i("显示侧就地修复并放回盘成功: %s", uri)
                true
            }
        }
    }

    private fun findByteRepair(original: FileChannel, repairedFile: File): ByteRepair? {
        if (original.size() != repairedFile.length()) return null
        var repair: ByteRepair? = null
        var offset = 0L
        val fixedBytes = ByteArray(16384)
        val source = ByteBuffer.allocate(16384)
        repairedFile.inputStream().use { input ->
            while (true) {
                val count = input.read(fixedBytes)
                if (count < 0) break
                source.clear()
                source.limit(count)
                while (source.hasRemaining()) {
                    if (original.read(source, offset + source.position()) <= 0) return null
                }
                for (i in 0 until count) {
                    val old = source.array()[i]
                    val fixed = fixedBytes[i]
                    if (old != fixed) {
                        // 同时确认 URI 仍对应最初读取的内容，没有被其他写入者替换。
                        if (repair != null || Integer.bitCount((old.toInt() xor fixed.toInt()) and 0xFF) != 1) {
                            return null
                        }
                        repair = ByteRepair(offset + i, old, fixed)
                    }
                }
                offset += count
            }
        }
        return repair.takeIf { offset == original.size() }
    }

    private fun isPng(header: ByteArray): Boolean {
        for (i in 0 until 8) {
            if (header[i] != PNG_MAGIC[i]) return false
        }
        return true
    }

    private fun isJpeg(header: ByteArray): Boolean {
        return (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte())
    }

    private fun isGif(header: ByteArray): Boolean {
        val s = String(header, 0, 6, Charsets.US_ASCII)
        return s == "GIF87a" || s == "GIF89a"
    }

    private fun isWebp(header: ByteArray): Boolean {
        return header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
    }

    private fun validateAndRepairPng(file: File): IntegrityResult {
        val fileLen = file.length()
        var pos = 8L
        var hasIhdr = false
        var hasIdat = false
        var hasIend = false

        val pendingRepairs = mutableListOf<PendingRepair>()

        val buf = ByteArray(16384)
        val headerBuf = ByteArray(8)
        val crcBuf = ByteArray(4)

        try {
            RandomAccessFile(file, "r").use { raf ->
                while (pos < fileLen) {
                    if (pos + 12L > fileLen) {
                        return IntegrityResult.error("PNG Chunk 头部不完整（pos=$pos）")
                    }

                    raf.seek(pos)
                    raf.readFully(headerBuf)
                    val chunkDataLen = ((headerBuf[0].toInt() and 0xFF) shl 24) or
                            ((headerBuf[1].toInt() and 0xFF) shl 16) or
                            ((headerBuf[2].toInt() and 0xFF) shl 8) or
                            (headerBuf[3].toInt() and 0xFF)

                    if (chunkDataLen < 0 || pos + 12L + chunkDataLen > fileLen) {
                        return IntegrityResult.error("PNG Chunk 长度越界（len=$chunkDataLen pos=$pos）")
                    }

                    val typeStr = String(headerBuf, 4, 4, Charsets.US_ASCII)
                    if (pos == 8L && typeStr != "IHDR") {
                        return IntegrityResult.error("PNG 首个 Chunk 不是 IHDR: $typeStr")
                    }
                    if (typeStr == "IHDR") hasIhdr = true
                    if (typeStr == "IDAT") hasIdat = true
                    if (typeStr == "IEND") hasIend = true

                    val totalChunkBytes = 4 + chunkDataLen
                    val crc = CRC32()
                    crc.update(headerBuf, 4, 4)

                    var remaining = chunkDataLen
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buf.size)
                        raf.readFully(buf, 0, toRead)
                        crc.update(buf, 0, toRead)
                        remaining -= toRead
                    }

                    raf.readFully(crcBuf)
                    val storedCrc = ((crcBuf[0].toInt() and 0xFF) shl 24) or
                            ((crcBuf[1].toInt() and 0xFF) shl 16) or
                            ((crcBuf[2].toInt() and 0xFF) shl 8) or
                            (crcBuf[3].toInt() and 0xFF)

                    val calcCrc = crc.value.toInt()

                    if (calcCrc != storedCrc) {
                        // 发现 CRC 校验失败，只允许单 Chunk 单比特翻转
                        if (pendingRepairs.isNotEmpty()) {
                            return IntegrityResult.error("PNG 存在多个损坏 Chunk，超出单比特自愈能力")
                        }

                        val flipLocation = findSingleBitFlip(totalChunkBytes, calcCrc, storedCrc)
                        if (flipLocation != null) {
                            val (relOffset, bit) = flipLocation
                            val absOffset = pos + 4L + relOffset
                            pendingRepairs.add(PendingRepair(absOffset, bit, typeStr))
                        } else {
                            return IntegrityResult.error("PNG Chunk $typeStr CRC 校验失败且无法自愈")
                        }
                    }

                    pos += 12L + chunkDataLen
                    if (typeStr == "IEND") {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            return IntegrityResult.error("PNG 解析异常: ${e.message}")
        }

        if (!hasIhdr || !hasIdat || !hasIend) {
            return IntegrityResult.error("PNG 缺失关键 Chunk (IHDR=$hasIhdr, IDAT=$hasIdat, IEND=$hasIend)")
        }

        if (pos != fileLen) {
            return IntegrityResult.error("PNG 末尾存在残留脏数据 (${fileLen - pos} 字节)")
        }

        // 无错误，直接返回 OK（未触碰磁盘）
        if (pendingRepairs.isEmpty()) {
            return IntegrityResult.OK
        }

        // —— 准则：只有真的修好才放回盘 ——
        val repair = pendingRepairs[0]
        // 元数据 Chunk 的 CRC 可修复也不代表图像数据可解码；所有写回都先验证 IDAT 流。
        val adlerVerified = verifyPngZlibStream(file, repair.absOffset, repair.bit)
        if (!adlerVerified) {
            return IntegrityResult.error("PNG 单比特自愈预检失败（zlib Adler-32 不匹配），保持原盘未动")
        }

        // 预检 100% 确认无假阳性，现在才写回原盘
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(repair.absOffset)
                val currByte = raf.read()
                val fixedByte = currByte xor (1 shl repair.bit)
                raf.seek(repair.absOffset)
                raf.write(fixedByte)
            }

            val desc = "自愈单比特翻转：chunk=${repair.typeStr} 偏移=0x${repair.absOffset.toString(16)} bit=${repair.bit}"
            Timber.tag("ImageIntegrity").i("PNG chunk %s CRC 自愈并放回盘成功: 偏移 %d (bit %d)", repair.typeStr, repair.absOffset, repair.bit)
            IntegrityResult.repaired(desc)
        } catch (e: Exception) {
            IntegrityResult.error("写入修复数据失败: ${e.message}")
        }
    }

    /**
     * 根据 32 位 CRC 伴随式定位单比特翻转位置。
     */
    internal fun findSingleBitFlip(
        totalLen: Int,
        calcCrc: Int,
        storedCrc: Int
    ): Pair<Int, Int>? {
        val syndrome = calcCrc xor storedCrc
        val deltas = IntArray(8) { bit -> CRC32_TABLE[1 shl bit] }

        for (d in 0 until totalLen) {
            for (bit in 0 until 8) {
                if (deltas[bit] == syndrome) {
                    val byteIdx = totalLen - 1 - d
                    return Pair(byteIdx, bit)
                }
            }
            for (bit in 0 until 8) {
                deltas[bit] = stepZeroByte(deltas[bit])
            }
        }
        return null
    }

    private fun verifyPngZlibStream(
        file: File,
        virtualFlipOffset: Long = -1L,
        virtualFlipBit: Int = 0
    ): Boolean {
        val inflater = Inflater(false)
        val readBuf = ByteArray(16384)
        val outBuf = ByteArray(16384)
        val headerBuf = ByteArray(8)

        try {
            FileInputStream(file).use { input ->
                val skipped = input.skip(8)
                if (skipped != 8L) return false
                var currentOffset = 8L

                while (true) {
                    var hRead = 0
                    while (hRead < 8) {
                        val n = input.read(headerBuf, hRead, 8 - hRead)
                        if (n < 0) break
                        hRead += n
                    }
                    if (hRead < 8) break
                    currentOffset += 8L

                    val length = ((headerBuf[0].toInt() and 0xFF) shl 24) or
                            ((headerBuf[1].toInt() and 0xFF) shl 16) or
                            ((headerBuf[2].toInt() and 0xFF) shl 8) or
                            (headerBuf[3].toInt() and 0xFF)

                    val typeStr = String(headerBuf, 4, 4, Charsets.US_ASCII)
                    if (typeStr == "IDAT") {
                        var remaining = length
                        while (remaining > 0) {
                            val toRead = minOf(remaining, readBuf.size)
                            val n = input.read(readBuf, 0, toRead)
                            if (n < 0) return false

                            // 虚拟翻转：在内存中将目标比特翻转后喂给 Inflater
                            if (virtualFlipOffset in currentOffset until (currentOffset + n)) {
                                val rel = (virtualFlipOffset - currentOffset).toInt()
                                readBuf[rel] = (readBuf[rel].toInt() xor (1 shl virtualFlipBit)).toByte()
                            }

                            currentOffset += n
                            remaining -= n
                            inflater.setInput(readBuf, 0, n)
                            while (!inflater.needsInput() && !inflater.finished()) {
                                val inflated = inflater.inflate(outBuf)
                                // PNG 不使用预置字典；needsDictionary 时 inflate 返回 0，
                                // 既不消耗输入也不结束，必须拒绝而不是在 IO worker 上空转。
                                if (inflated == 0 && !inflater.needsInput() && !inflater.finished()) {
                                    return false
                                }
                            }
                        }
                    } else {
                        var toSkip = length.toLong()
                        while (toSkip > 0) {
                            val s = input.skip(toSkip)
                            if (s <= 0) break
                            toSkip -= s
                            currentOffset += s
                        }
                    }

                    // 跳过 4 字节 CRC
                    var crcSkip = 4L
                    while (crcSkip > 0) {
                        val s = input.skip(crcSkip)
                        if (s <= 0) break
                        crcSkip -= s
                        currentOffset += s
                    }

                    if (typeStr == "IEND") break
                }
            }
            return inflater.finished()
        } catch (_: Exception) {
            return false
        } finally {
            inflater.end()
        }
    }

    private fun validateJpeg(file: File): IntegrityResult {
        val len = file.length()
        if (len < 128L) return IntegrityResult.error("JPEG 文件过小")

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val tailLen = minOf(len, 256L).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(len - tailLen)
                raf.readFully(tail)

                var i = tailLen - 1
                while (i >= 0 && (tail[i] == 0.toByte() || tail[i] == 0x0A.toByte() || tail[i] == 0x0D.toByte() || tail[i] == 0x20.toByte())) {
                    i--
                }
                if (i >= 1 && tail[i - 1] == 0xFF.toByte() && tail[i] == 0xD9.toByte()) {
                    IntegrityResult.OK
                } else {
                    IntegrityResult.error("JPEG 缺少 EOI 结束标记（文件截断）")
                }
            }
        } catch (e: Exception) {
            IntegrityResult.error("JPEG 校验异常: ${e.message}")
        }
    }

    private fun validateGif(file: File): IntegrityResult {
        val len = file.length()
        if (len < 16L) return IntegrityResult.error("GIF 文件过小")

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - 1)
                val trailer = raf.read()
                if (trailer == 0x3B) {
                    IntegrityResult.OK
                } else {
                    IntegrityResult.error("GIF 缺少 0x3B 结束标记（文件截断）")
                }
            }
        } catch (e: Exception) {
            IntegrityResult.error("GIF 校验异常: ${e.message}")
        }
    }

    private fun validateWebp(file: File): IntegrityResult {
        val len = file.length()
        if (len < 16L) return IntegrityResult.error("WebP 文件过小")

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(4)
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                val riffSize = (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
                val expectedFileLen = (riffSize.toLong() and 0xFFFFFFFFL) + 8L
                if (len < expectedFileLen) {
                    IntegrityResult.error("WebP 长度不足（声明 $expectedFileLen 字节，实际 $len 字节）")
                } else {
                    IntegrityResult.OK
                }
            }
        } catch (e: Exception) {
            IntegrityResult.error("WebP 校验异常: ${e.message}")
        }
    }
}
