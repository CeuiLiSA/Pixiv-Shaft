package ceui.pixiv.sticker

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StickerStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = linkedMapOf<String, ByteArray>()
    private fun catalog(badPath: Boolean = false): StickerCatalog {
        fun pack(type: String, id: Long): StickerPack {
            val prefix = if (type == "animation") "sticker/animation" else "emoji"
            val packages = listOf(64, 128).map { size ->
                val archive = ByteArrayOutputStream().also { buffer ->
                    ZipOutputStream(buffer).use { zip ->
                        zip.putNextEntry(ZipEntry(if (badPath) "../escape.png" else "$prefix/$size/x.png"))
                        zip.write("$prefix/$size sample image".toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()
                val url = "https://${StickerCatalog.COS_HOST}/public/stickers/$prefix/pkg-$size.zip"
                bytes[url] = archive
                StickerPackage(size, size, url, "$prefix/$size", archive.size.toLong(), StickerStore.hash(archive))
            }
            val sticker = Sticker(id, "sample", StickerMedia("https://fbase.fivedegrees.ai/$prefix/256/x.png",
                packages.map { StickerResource(it.width, it.height, "https://fbase.fivedegrees.ai/${it.path}/x.png") }))
            return StickerPack(packages, listOf(StickerGroup(type, listOf(sticker))), listOf(sticker))
        }
        return StickerCatalog(StickerVersions(StickerCatalog.TYPES.map { StickerVersion(it, "1") }),
            linkedMapOf("customized" to pack("customized", 650863185465585230L),
                "static" to pack("static", 282611954856142627L), "animation" to pack("animation", 1123418376571372434L)))
    }
    private fun download(pkg: StickerPackage, file: File, progress: (Long) -> Unit) {
        file.writeBytes(bytes.getValue(pkg.url)); progress(file.length())
    }

    @Test fun `all four ZIPs and extraction markers precede the single ready marker`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        var downloads = 0
        val ready = store.prepare(catalog, { pkg, target, progress ->
            assertFalse(File(root, "ready.json").exists())
            downloads++; download(pkg, target, progress)
        }, { _, _, _ -> })
        assertEquals(4, downloads)
        assertTrue(store.hasReadyMarker(ready.generation))
        assertTrue(store.isUnchanged(ready))
        assertEquals(3, ready.images.size)
        catalog.packages().forEach {
            val dir = File(root, "packages/${it.sha256}")
            assertTrue(File(dir, "archive.zip").isFile)
            assertTrue(File(dir, "downloaded.json").isFile)
            assertTrue(File(dir, "extracted.json").isFile)
            assertTrue(File(dir, "files/${it.path}/x.png").isFile)
        }
        assertEquals(650863185465585230L, store.savedCatalog()!!.packs.getValue("customized").stickers().single().stickerId)
        assertTrue(ready.file(650863185465585230L, 64)!!.path.contains("/emoji/64/"))
        assertTrue(ready.file(650863185465585230L, 128)!!.path.contains("/emoji/128/"))
        assertTrue(ready.file(650863185465585230L, 256)!!.path.contains("128"))
        File(root, "packages/${catalog.packages().last().sha256}/downloaded.json").delete()
        assertFalse(store.isUnchanged(ready))
    }

    @Test fun `interrupted install never opens panel and retry reuses verified ZIPs`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        var calls = 0
        assertThrows(java.io.IOException::class.java) {
            store.prepare(catalog, { pkg, file, progress ->
                if (++calls == 4) throw java.io.IOException("Interrupted")
                download(pkg, file, progress)
            }, { _, _, _ -> })
        }
        assertFalse(File(root, "ready.json").exists())
        calls = 0
        val ready = store.prepare(catalog, { p, f, progress -> calls++; download(p, f, progress) }, { _, _, _ -> })
        assertEquals(1, calls)
        assertTrue(store.hasReadyMarker(ready.generation))
    }

    @Test fun `legacy COS catalog reuses installed files and repairs only missing ZIP via GitHub`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        val installed = store.prepare(catalog, ::download, { _, _, _ -> })
        assertEquals(installed.generation, store.reopen()!!.generation)
        val missing = catalog.packages().last()
        assertTrue(File(root, "packages/${missing.sha256}/archive.zip").delete())
        var downloads = 0
        val repaired = store.prepare(store.savedCatalog()!!, { pkg, target, progress ->
            downloads++
            assertEquals(missing.sha256, pkg.sha256)
            assertEquals("${StickerDownloadSource.RELEASE_BASE}${missing.sha256}.zip", StickerDownloadSource.url(pkg))
            download(pkg, target, progress)
        }, { _, _, _ -> })
        assertEquals(1, downloads)
        assertEquals(installed.generation, repaired.generation)
        assertTrue(store.isUnchanged(repaired))
    }

    @Test fun `missing or corrupted extracted files are repaired locally before ready`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        val first = store.prepare(catalog, ::download, { _, _, _ -> })
        val image = first.file(650863185465585230L, 64)!!
        image.writeBytes(ByteArray(image.length().toInt())) // Same size, wrong CRC.
        File(root, "packages/${catalog.packages().last().sha256}/extracted.json").delete()
        val ready = store.prepare(catalog, { _, _, _ -> fail("Verified ZIP must be reused") }, { _, _, _ -> })
        assertTrue(image.readText().contains("sample image"))
        assertTrue(store.hasReadyMarker(ready.generation))
    }

    @Test fun `bad ZIP checksum and path traversal cannot create ready marker`() {
        val root = folder.newFolder()
        val catalog = catalog()
        assertThrows(IllegalStateException::class.java) {
            StickerStore(root).prepare(catalog, { pkg, file, _ -> file.writeBytes(ByteArray(pkg.size.toInt())) }, { _, _, _ -> })
        }
        assertFalse(File(root, "ready.json").exists())
        val bad = catalog(badPath = true)
        assertThrows(IllegalArgumentException::class.java) { StickerStore(root).prepare(bad, ::download, { _, _, _ -> }) }
        assertFalse(File(root, "ready.json").exists())
        assertFalse(File(root, "escape.png").exists())
    }

    @Test fun `warm reopen republishes a finished install and never downloads`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        assertNull("Nothing installed must stay untouched", store.reopen())
        val installed = store.prepare(catalog, ::download, { _, _, _ -> })
        val warm = store.reopen()!!
        assertEquals(installed.generation, warm.generation)
        assertEquals(installed.images.keys, warm.images.keys)
        assertEquals(installed.inventory.size, warm.inventory.size)
        assertTrue(store.isUnchanged(warm))
        assertTrue(warm.file(650863185465585230L, 64)!!.path.contains("/emoji/64/"))
    }

    @Test fun `warm reopen refuses a closed gate or an incomplete install`() {
        val catalog = catalog()
        val root = folder.newFolder()
        val store = StickerStore(root)
        store.prepare(catalog, ::download, { _, _, _ -> })
        val image = File(root, "packages/${catalog.packages().first().sha256}/files/${catalog.packages().first().path}/x.png")
        val backup = image.readBytes()
        assertTrue(image.delete())
        assertNull("A missing image must not reopen", store.reopen())
        image.writeBytes(backup.copyOf(backup.size - 1))
        assertNull("A truncated image must not reopen", store.reopen())
        image.writeBytes(backup)
        assertNotNull(store.reopen())
        assertTrue(File(root, "ready.json").delete())
        assertNull("Without the gate there is nothing to reopen", store.reopen())
        // Reading is all it ever does: the install is still exactly where prepare left it.
        catalog.packages().forEach { assertTrue(File(root, "packages/${it.sha256}/archive.zip").isFile) }
    }

    @Test fun `API host cannot be used for ZIP bytes and missing variants fail validation`() {
        val catalog = catalog()
        val bad = catalog.copy(packs = catalog.packs.mapValues { (_, pack) ->
            pack.copy(pkgList = pack.pkgList.map { it.copy(url = it.url.replace(StickerCatalog.COS_HOST, "api.pixshaft.com")) })
        })
        assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        assertThrows(IllegalArgumentException::class.java) { catalog.copy(packs = emptyMap()).validate() }
    }
}
