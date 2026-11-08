package ceui.pixiv.ui.common

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** 模型包先在暂存目录完整校验，提交失败时恢复旧目录。 */
internal object ModelArchiveInstaller {
    fun isReady(dir: File, requiredFiles: List<String>): Boolean =
        requiredFiles.isNotEmpty() && requiredFiles.all {
            File(dir, it).let { file -> file.isFile && file.length() > 0L }
        }

    @Synchronized
    fun install(archive: File, dir: File, requiredFiles: List<String>): Boolean {
        archive.inputStream().use { input ->
            if (!input.readNBytesCompat(4).contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) return false
        }
        val backup = File(dir.parentFile, dir.name + ".backup")
        if (backup.exists()) {
            if (!dir.exists()) {
                if (!backup.renameTo(dir)) throw IOException("cannot recover model backup")
            } else if (isReady(dir, requiredFiles)) {
                if (!backup.deleteRecursively()) throw IOException("cannot remove old model backup")
            } else {
                throw IOException("incomplete model with an unresolved backup")
            }
        }
        val staging = File(dir.parentFile, dir.name + ".staging-" + UUID.randomUUID())
        try {
            if (!staging.mkdirs()) throw IOException("cannot create model staging directory")
            val prefix = staging.canonicalPath + File.separator
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val file = File(staging, entry.name)
                        if (!file.canonicalPath.startsWith(prefix)) {
                            throw SecurityException("zip entry escapes target dir: ${entry.name}")
                        }
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!isReady(staging, requiredFiles)) return false
            if (dir.exists() && !dir.renameTo(backup)) throw IOException("cannot back up installed model")
            if (!staging.renameTo(dir)) {
                if (backup.exists() && !backup.renameTo(dir)) {
                    throw IOException("cannot commit model or restore backup: ${backup.path}")
                }
                throw IOException("cannot commit model installation")
            }
            backup.deleteRecursively()
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    // InputStream.readNBytes 是较新平台 API；minSdk 24 用普通 read 循环。
    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) return bytes.copyOf(offset)
            offset += read
        }
        return bytes
    }
}
