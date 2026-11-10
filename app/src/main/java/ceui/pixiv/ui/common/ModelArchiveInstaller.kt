package ceui.pixiv.ui.common

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 模型包先在暂存目录完整校验，提交失败时恢复旧目录。 */
internal object ModelArchiveInstaller {
    fun isReady(dir: File, requiredFiles: List<String>): Boolean =
        requiredFiles.isNotEmpty() && requiredFiles.all {
            File(dir, it).let { file -> file.isFile && file.length() > 0L }
        }

    fun recoverReady(dir: File, requiredFiles: List<String>): Boolean {
        val backup = File(dir.parentFile, dir.name + ".backup")
        if (!backup.exists()) return isReady(dir, requiredFiles)
        synchronized(this) {
            if (!backup.exists()) return isReady(dir, requiredFiles)
            if (!dir.exists()) {
                backup.renameTo(dir)
            } else if (isReady(dir, requiredFiles)) {
                backup.deleteRecursively()
            } else if (isReady(backup, requiredFiles)) {
                if (dir.deleteRecursively()) backup.renameTo(dir)
            } else {
                backup.deleteRecursively()
            }
            return isReady(dir, requiredFiles)
        }
    }

    fun hasTempArtifacts(cacheDir: File, dir: File, assetDir: String): Boolean =
        File(dir.parentFile, dir.name + ".staging").exists() ||
            File(cacheDir, "model_dl_${assetDir}.zip").exists() ||
            File(cacheDir, "model_import_${assetDir}.zip").exists()

    @Synchronized
    fun cleanTempArtifacts(cacheDir: File, dir: File, assetDir: String) {
        File(dir.parentFile, dir.name + ".staging").deleteRecursively()
        File(cacheDir, "model_dl_${assetDir}.zip").delete()
        File(cacheDir, "model_import_${assetDir}.zip").delete()
    }

    @Synchronized
    fun cleanResidue(cacheDir: File, dir: File, assetDir: String, requiredFiles: List<String>) {
        if (!recoverReady(dir, requiredFiles)) dir.deleteRecursively()
        val backup = File(dir.parentFile, dir.name + ".backup")
        if (backup.exists() && !isReady(backup, requiredFiles)) backup.deleteRecursively()
        cleanTempArtifacts(cacheDir, dir, assetDir)
    }

    @Synchronized
    fun install(
        archive: File,
        dir: File,
        requiredFiles: List<String>,
        checkCancelled: () -> Unit = {},
    ): Boolean {
        checkCancelled()
        val staging = File(dir.parentFile, dir.name + ".staging")
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("cannot remove old model staging directory")
        }
        val backup = File(dir.parentFile, dir.name + ".backup")
        recoverReady(dir, requiredFiles)
        if (backup.exists()) throw IOException("cannot resolve model backup")
        archive.inputStream().use { input ->
            if (!input.readNBytesCompat(4).contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) return false
        }
        try {
            if (!staging.mkdirs()) throw IOException("cannot create model staging directory")
            val prefix = staging.canonicalPath + File.separator
            ZipInputStream(archive.inputStream()).use { zip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entry = zip.nextEntry
                while (entry != null) {
                    checkCancelled()
                    if (!entry.isDirectory) {
                        val file = File(staging, entry.name)
                        if (!file.canonicalPath.startsWith(prefix)) {
                            throw SecurityException("zip entry escapes target dir: ${entry.name}")
                        }
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            while (true) {
                                checkCancelled()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!isReady(staging, requiredFiles)) return false
            checkCancelled()
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

    @Synchronized
    fun prepareTempArchive(cacheDir: File, dir: File, assetDir: String, importing: Boolean = false): File {
        cleanTempArtifacts(cacheDir, dir, assetDir)
        val prefix = if (importing) "model_import_" else "model_dl_"
        return File(cacheDir, "$prefix${assetDir}.zip")
    }

    @Synchronized
    fun delete(dir: File, cacheDir: File, assetDir: String) {
        dir.deleteRecursively()
        File(dir.parentFile, dir.name + ".backup").deleteRecursively()
        cleanTempArtifacts(cacheDir, dir, assetDir)
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
