package ceui.pixiv.ui.common

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** 模型包先在暂存目录完整校验，提交失败时恢复旧目录。 */
internal object ModelArchiveInstaller {
    // 解压期间不持有清理锁，删除入口无需等待整包解压；活跃暂存目录单独保留。
    private val artifactsLock = Any()
    private val activeArchives = mutableSetOf<File>()
    private var activeStaging: File? = null
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun isReady(dir: File, requiredFiles: List<String>): Boolean =
        requiredFiles.isNotEmpty() && requiredFiles.all {
            File(dir, it).let { file -> file.isFile && file.length() > 0L }
        }

    @Synchronized
    fun install(archive: File, dir: File, requiredFiles: List<String>): Boolean {
        synchronized(artifactsLock) { cleanStaging(dir) }
        archive.inputStream().use { input ->
            if (!input.readNBytesCompat(4).contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) return false
        }
        val backup = File(dir.parentFile, dir.name + ".backup")
        synchronized(artifactsLock) {
            if (backup.exists()) {
                if (!dir.exists()) {
                    if (!backup.renameTo(dir)) throw IOException("cannot recover model backup")
                } else if (isReady(dir, requiredFiles)) {
                    if (!backup.deleteRecursively()) throw IOException("cannot remove old model backup")
                } else {
                    throw IOException("incomplete model with an unresolved backup")
                }
            }
        }
        val staging = synchronized(artifactsLock) {
            File(dir.parentFile, dir.name + ".staging-" + UUID.randomUUID()).absoluteFile.also {
                activeStaging = it
            }
        }
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
            synchronized(artifactsLock) {
                if (dir.exists() && !dir.renameTo(backup)) throw IOException("cannot back up installed model")
                if (!staging.renameTo(dir)) {
                    if (backup.exists() && !backup.renameTo(dir)) {
                        throw IOException("cannot commit model or restore backup: ${backup.path}")
                    }
                    throw IOException("cannot commit model installation")
                }
                backup.deleteRecursively()
            }
            return true
        } finally {
            synchronized(artifactsLock) {
                staging.deleteRecursively()
                activeStaging = null
            }
        }
    }

    fun createTempArchive(
        cacheDir: File, dir: File, assetDir: String, importing: Boolean = false,
    ): File = synchronized(artifactsLock) {
        // 下载前先释放上次解压占用的空间，避免等新 ZIP 写满缓存后才有机会清理。
        cleanStaging(dir)
        cleanArchives(cacheDir, assetDir)
        val prefix = if (importing) "model_import_" else "model_dl_"
        File(cacheDir, "$prefix${assetDir}_${UUID.randomUUID()}.zip").absoluteFile.also {
            // 先登记再交给调用方写入；其他下载/导入清理时必须保留这个文件。
            activeArchives.add(it)
        }
    }

    fun releaseTempArchive(archive: File) {
        synchronized(artifactsLock) {
            archive.delete()
            activeArchives.remove(archive.absoluteFile)
        }
    }

    fun delete(dir: File, cacheDir: File, assetDir: String) {
        synchronized(artifactsLock) {
            dir.deleteRecursively()
            File(dir.parentFile, dir.name + ".backup").deleteRecursively()
            cleanStaging(dir)
            cleanArchives(cacheDir, assetDir)
        }
    }

    private fun cleanStaging(dir: File) {
        dir.parentFile?.listFiles().orEmpty().filter {
            it.isDirectory && it.absoluteFile != activeStaging && (it.name == dir.name + ".staging" ||
                isTemporaryName(it.name, dir.name + ".staging-"))
        }.forEach { it.deleteRecursively() }
    }

    private fun cleanArchives(cacheDir: File, assetDir: String) {
        cacheDir.listFiles().orEmpty().filter { file ->
            file.isFile && file.absoluteFile !in activeArchives &&
                listOf("model_dl_${assetDir}", "model_import_${assetDir}").any { prefix ->
                    file.name == "$prefix.zip" || isTemporaryName(file.name, "${prefix}_", ".zip")
                }
        }.forEach { it.delete() }
    }

    private fun isTemporaryName(name: String, prefix: String, suffix: String = ""): Boolean =
        name.startsWith(prefix) && name.endsWith(suffix) &&
            uuid.matches(name.removePrefix(prefix).removeSuffix(suffix))

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
