package ceui.pixiv.snapshot

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .shaftsnap 本质是 ZIP。这里只负责目录 <=> ZIP 字节的转换，
 * 不碰 SAF、不碰快照库目录规划。
 */
object SnapshotArchive {

    /** 把整个快照目录（含 manifest/json/图片）打成 ZIP 写到 [out]。 */
    fun zipDirectory(dir: File, out: OutputStream) {
        if (!dir.isDirectory) throw SnapshotException("快照目录不存在: ${dir.absolutePath}")
        ZipOutputStream(out.buffered()).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /** 解压 ZIP 到 [targetDir]，过滤掉路径穿越条目。 */
    fun unzip(input: InputStream, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = sanitizeEntryName(entry.name)
                if (name.isNotEmpty() && !entry.isDirectory) {
                    val target = File(targetDir, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun sanitizeEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
        val cleaned = normalized
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        if (cleaned.startsWith("/") || cleaned.contains("../")) {
            throw SnapshotException("非法快照条目: $name")
        }
        return cleaned
    }
}