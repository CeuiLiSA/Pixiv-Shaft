package ceui.pixiv.sticker

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Disk transaction used only on Dispatchers.IO, serialized by StickerRepository. */
class StickerStore(private val root: File, private val event: (String) -> Unit = {}) {
    private val gson = Gson()
    private data class ExtractedFile(val path: String, val size: Long, val crc: Long)
    private data class Extraction(val sha256: String, val files: List<ExtractedFile>)
    data class VerifiedFile(val file: File, val size: Long, val modified: Long)
    data class Ready(val generation: String, val catalog: StickerCatalog, val images: Map<Long, List<Pair<Int, File>>>,
        val inventory: List<VerifiedFile> = emptyList()) {
        fun file(id: Long, size: Int): File? {
            val variants = images[id].orEmpty().sortedBy { it.first }
            return (variants.firstOrNull { it.first >= size } ?: variants.lastOrNull())?.second
        }
    }

    fun savedCatalog(): StickerCatalog? = File(root, "catalog.json").takeIf { it.isFile }?.let {
        gson.fromJson(it.readText(), StickerCatalog::class.java).also { catalog -> catalog.validate() }
    }

    fun prepare(
        catalog: StickerCatalog,
        download: (StickerPackage, File, (Long) -> Unit) -> Unit,
        progress: (String, Long, Long) -> Unit,
        checkCancelled: () -> Unit = {},
    ): Ready {
        catalog.validate()
        check(root.mkdirs() || root.isDirectory)
        val raw = gson.toJson(catalog)
        val generation = hash(raw.toByteArray())
        event("verify_start generation=$generation root=${root.path} packages=${catalog.packages().size}")
        // This is the global gate. A partial/repaired generation cannot remain ready.
        val readyMarker = File(root, "ready.json")
        if (readyMarker.exists() && !readyMarker.delete()) throw IOException("Cannot invalidate sticker marker")
        event("gate_closed marker=${readyMarker.path}")
        val packages = catalog.packages()
        val total = packages.sumOf { it.size }
        var completed = 0L
        val inventory = ArrayList<VerifiedFile>()
        for (pkg in packages) {
            checkCancelled()
            val dir = File(root, "packages/${pkg.sha256}")
            check(dir.mkdirs() || dir.isDirectory)
            val zip = File(dir, "archive.zip")
            val downloaded = File(dir, "downloaded.json")
            val extracted = File(dir, "extracted.json")
            val files = File(dir, "files")
            val zipValid = zip.isFile && zip.length() == pkg.size && hash(zip, checkCancelled) == pkg.sha256
            event("zip_check path=${zip.path} valid=$zipValid expected_bytes=${pkg.size}")
            if (!zipValid) {
                extracted.delete()
                downloaded.delete()
                val partial = File(dir, "archive.zip.part")
                partial.delete()
                try {
                    progress("download", completed, total)
                    event("download_start url=${pkg.url} destination=${partial.path}")
                    download(pkg, partial) { bytes -> checkCancelled(); progress("download", completed + bytes, total) }
                    check(partial.length() == pkg.size && hash(partial, checkCancelled) == pkg.sha256) { "Sticker ZIP checksum mismatch" }
                    check(partial.renameTo(zip)) { "Cannot commit sticker ZIP" }
                    event("download_verified file=${zip.path} bytes=${pkg.size} sha256=${pkg.sha256}")
                } finally {
                    partial.delete()
                }
            }
            // Retain both the ZIP and its marker, including after extraction.
            atomicWrite(downloaded, gson.toJson(pkg))
            event("download_marker path=${downloaded.path}")
            progress("extract", completed, total)
            if (!validExtraction(extracted, files, pkg, checkCancelled)) {
                event("extract_start zip=${zip.path} destination=${files.path}")
                extracted.delete()
                if (files.exists()) check(files.deleteRecursively()) { "Cannot clear incomplete extraction" }
                check(files.mkdirs())
                val records = ArrayList<ExtractedFile>()
                var expanded = 0L
                ZipFile(zip).use { archive ->
                    require(archive.size() in 1..50000) { "Invalid sticker ZIP entry count" }
                    val entries = archive.entries()
                    while (entries.hasMoreElements()) {
                        checkCancelled()
                        val entry = entries.nextElement()
                        val target = safeChild(files, entry.name)
                        if (entry.isDirectory) {
                            check(target.mkdirs() || target.isDirectory)
                            continue
                        }
                        require(entry.size in 0..64L * 1024 * 1024 && entry.crc >= 0)
                        expanded += entry.size
                        require(expanded <= 1024L * 1024 * 1024) { "Sticker ZIP expands beyond limit" }
                        check(!target.exists()) { "Duplicate ZIP entry" }
                        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        val crc = CRC32()
                        var written = 0L
                        archive.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    checkCancelled()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    written += n
                                    require(written <= entry.size)
                                    crc.update(buffer, 0, n)
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        check(written == entry.size && crc.value == entry.crc) { "Corrupt extracted sticker" }
                        records.add(ExtractedFile(entry.name, written, crc.value))
                    }
                }
                require(records.isNotEmpty())
                atomicWrite(extracted, gson.toJson(Extraction(pkg.sha256, records)))
                event("extract_verified files=${records.size} bytes=$expanded marker=${extracted.path}")
            } else {
                event("extract_reused marker=${extracted.path}")
            }
            completed += pkg.size
            val records = gson.fromJson(extracted.readText(), Extraction::class.java).files
            for (file in listOf(zip, downloaded, extracted) + records.map { safeChild(files, it.path) }) {
                inventory.add(VerifiedFile(file, file.length(), file.lastModified()))
            }
        }
        val images = resolveImages(catalog)
        checkCancelled()
        atomicWrite(File(root, "catalog.json"), raw)
        // The only operation which opens the panel gate, after all ZIPs AND files.
        atomicWrite(readyMarker, generation)
        event("gate_ready generation=$generation packages=${packages.size} stickers=${images.size} marker=${readyMarker.path}")
        progress("ready", total, total)
        return Ready(generation, catalog, images, inventory)
    }

    private fun resolveImages(catalog: StickerCatalog): Map<Long, List<Pair<Int, File>>> {
        val images = linkedMapOf<Long, List<Pair<Int, File>>>()
        for (pack in catalog.packs.values) for (sticker in pack.stickers()) {
            val local = pack.pkgList.map { pkg ->
                val resource = sticker.media.resourceList.firstOrNull {
                    it.width == pkg.width && URI(it.url).path.removePrefix("/").startsWith(pkg.path + "/")
                } ?: throw IOException("Sticker ${sticker.stickerId} has no local ${pkg.width} resource")
                val file = safeChild(File(root, "packages/${pkg.sha256}/files"), URI(resource.url).path.removePrefix("/"))
                check(file.isFile && file.length() > 0) { "Missing local sticker ${sticker.stickerId}" }
                pkg.width to file
            }
            images[sticker.stickerId] = local
        }
        return images
    }

    /**
     * Warm start for an installation a previous process already completed.
     *
     * Read-only by construction: no download, no extraction, no marker is written or
     * deleted, so a device with no installation - or half of one - is left exactly as it
     * was and just gets null back. The ready marker is the proof that every SHA-256 and
     * CRC32 was checked once; re-proving it costs seconds of hashing over hundreds of MB,
     * so reopening settles for each file's identity (presence and exact size), the same
     * standard [isUnchanged] already applies to an in-process reopen.
     */
    fun reopen(checkCancelled: () -> Unit = {}): Ready? {
        if (!File(root, "ready.json").isFile) return null
        val catalog = try { savedCatalog() } catch (error: Exception) {
            event("reopen_skipped reason=invalid_catalog")
            return null
        } ?: return null
        val generation = hash(gson.toJson(catalog).toByteArray())
        if (!hasReadyMarker(generation)) {
            event("reopen_skipped reason=stale_marker generation=$generation")
            return null
        }
        val inventory = ArrayList<VerifiedFile>()
        for (pkg in catalog.packages()) {
            checkCancelled()
            val dir = File(root, "packages/${pkg.sha256}")
            val zip = File(dir, "archive.zip")
            val downloaded = File(dir, "downloaded.json")
            val extracted = File(dir, "extracted.json")
            if (!zip.isFile || zip.length() != pkg.size || !downloaded.isFile || !extracted.isFile) {
                event("reopen_skipped reason=incomplete_package path=${dir.path}")
                return null
            }
            val extraction = try { gson.fromJson(extracted.readText(), Extraction::class.java) } catch (_: Exception) { null }
            if (extraction == null || extraction.sha256 != pkg.sha256 || extraction.files.isEmpty()) {
                event("reopen_skipped reason=invalid_extraction path=${extracted.path}")
                return null
            }
            val files = File(dir, "files")
            for (record in extraction.files) {
                checkCancelled()
                val file = try { safeChild(files, record.path) } catch (error: Exception) {
                    event("reopen_skipped reason=unsafe_path")
                    return null
                }
                if (!file.isFile || file.length() != record.size) {
                    event("reopen_skipped reason=missing_file path=${file.path}")
                    return null
                }
                inventory.add(VerifiedFile(file, record.size, file.lastModified()))
            }
            for (file in listOf(zip, downloaded, extracted)) inventory.add(VerifiedFile(file, file.length(), file.lastModified()))
        }
        val images = try { resolveImages(catalog) } catch (error: Exception) {
            event("reopen_skipped reason=${error.javaClass.simpleName}")
            return null
        }
        checkCancelled()
        event("reopen_ready generation=$generation packages=${catalog.packages().size} stickers=${images.size} files=${inventory.size}")
        return Ready(generation, catalog, images, inventory)
    }

    // Full SHA/CRC verification has already run in this process. Reopening checks
    // every file/marker's identity without rereading hundreds of MB of image bytes.
    fun isUnchanged(ready: Ready): Boolean = ready.inventory.isNotEmpty() && hasReadyMarker(ready.generation) &&
        ready.inventory.all { it.file.isFile && it.file.length() == it.size && it.file.lastModified() == it.modified }

    fun hasReadyMarker(generation: String): Boolean =
        File(root, "ready.json").let { it.isFile && it.readText() == generation }

    private fun validExtraction(marker: File, files: File, pkg: StickerPackage, checkCancelled: () -> Unit): Boolean {
        if (!marker.isFile) return false
        val extraction = try { gson.fromJson(marker.readText(), Extraction::class.java) } catch (_: Exception) { return false } ?: return false
        if (extraction.sha256 != pkg.sha256 || extraction.files.isEmpty()) return false
        return extraction.files.all { record ->
            checkCancelled()
            val file = safeChild(files, record.path)
            if (!file.isFile || file.length() != record.size) false else {
                val crc = CRC32()
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                    }
                }
                crc.value == record.crc
            }
        }
    }

    companion object {
        internal fun safeChild(root: File, relative: String): File {
            require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative)
            val file = File(root, relative).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe ZIP path" }
            return file
        }

        private fun atomicWrite(file: File, text: String) {
            val temporary = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temporary).use { it.write(text.toByteArray()); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot commit ${file.name}" }
        }

        internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(file: File, checkCancelled: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
