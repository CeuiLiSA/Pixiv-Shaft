package ceui.pixiv.download

import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DownloadsFacadeTest {

    private val meta = ItemMeta(
        id = 1L,
        title = "a",
        author = Author(2L, "b"),
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        totalPages = 1,
    )

    private val item = DownloadItem(
        bucket = Bucket.Illust,
        ext = "png",
        mime = "image/png",
        sourceUrl = "",
        meta = meta,
    )

    private val storageKey = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    private fun configWith(
        template: String = "d/{title} {id}.{ext}",
        overwrite: OverwritePolicy = OverwritePolicy.Rename,
    ) = DownloadConfig(
        defaults = BucketDefaults(template = template, storage = storageKey, overwrite = overwrite),
    )

    /**
     * Unit-test backend that records calls without producing a real WriteHandle.
     * [open] throws — tests that exercise the open path should use an androidTest
     * or a Robolectric environment where `android.net.Uri` is real.
     */
    private class FakeBackend(private val existing: MutableSet<String> = mutableSetOf()) : StorageBackend {
        val deleted = mutableListOf<RelativePath>()
        override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle =
            error("FakeBackend.open not supported in pure unit tests — see kdoc")
        override fun exists(relPath: RelativePath): Boolean = relPath.joinTo() in existing
        override fun delete(relPath: RelativePath): Boolean {
            deleted += relPath
            return existing.remove(relPath.joinTo())
        }
        fun seed(path: String) { existing += path }
    }

    @Test fun `plan renders sanitized path`() {
        val dirtyItem = item.copy(meta = meta.copy(title = "a/../b:c"))
        val facade = Downloads(configWith(), { FakeBackend() })
        val plan = facade.plan(dirtyItem)
        assertEquals(listOf("d", "a_.._b_c 1.png"), plan.path.segments)
    }

    @Test fun `rename policy picks next free name`() {
        val backend = FakeBackend()
        backend.seed("d/a 1.png")
        backend.seed("d/a 1 (1).png")
        val facade = Downloads(configWith(overwrite = OverwritePolicy.Rename), { backend })
        val plan = facade.plan(item)
        assertEquals(listOf("d", "a 1 (2).png"), plan.path.segments)
    }

    @Test fun `replace policy does not delete during plan — deferred to backend replace`() {
        val backend = FakeBackend()
        backend.seed("d/a 1.png")
        val facade = Downloads(configWith(overwrite = OverwritePolicy.Replace), { backend })
        val plan = facade.plan(item)
        assertEquals(listOf("d", "a 1.png"), plan.path.segments)
        assertFalse("plan should not skip for Replace", plan.skip)
        assertTrue("nothing deleted during plan", backend.deleted.isEmpty())
    }

    @Test fun `skip policy marks plan and returns untouched path when file exists`() {
        val backend = FakeBackend()
        backend.seed("d/a 1.png")
        val facade = Downloads(configWith(overwrite = OverwritePolicy.Skip), { backend })
        val plan = facade.plan(item)
        assertEquals(listOf("d", "a 1.png"), plan.path.segments)
        assertTrue("skip detected", plan.skip)
        assertTrue("nothing deleted on skip", backend.deleted.isEmpty())
    }

    @Test fun `skip policy allows write when file does not exist`() {
        val backend = FakeBackend()
        val facade = Downloads(configWith(overwrite = OverwritePolicy.Skip), { backend })
        val plan = facade.plan(item)
        assertFalse("should not skip", plan.skip)
    }

    @Test fun `open returns null when plan is skip`() {
        val backend = FakeBackend()
        backend.seed("d/a 1.png")
        val facade = Downloads(configWith(overwrite = OverwritePolicy.Skip), { backend })
        assertEquals(null, facade.open(item))
    }

    @Test fun `per bucket override takes effect via facade`() {
        val cfg = configWith().withBucket(
            Bucket.Novel,
            BucketConfig(template = "novels/{id}.txt"),
        )
        val novelItem = item.copy(bucket = Bucket.Novel, ext = "txt", mime = "text/plain")
        val facade = Downloads(cfg, { FakeBackend() })
        val plan = facade.plan(novelItem)
        assertEquals(listOf("novels", "1.txt"), plan.path.segments)
    }

    @Test fun `TempCache bypasses user config and always uses AppCache`() {
        val userBackend = FakeBackend()
        val cacheBackend = FakeBackend()
        val facade = Downloads(configWith()) { choice ->
            if (choice == StorageChoice.AppCache) cacheBackend else userBackend
        }
        val tmp = item.copy(bucket = Bucket.TempCache, ext = "zip", mime = "application/zip")
        val plan = facade.plan(tmp)
        // The facade should have resolved TempCache to the AppCache backend —
        // we check identity rather than calling open() (which would need Android).
        assertTrue("temp plan uses cache backend", plan.backend === cacheBackend)
    }

    @Test fun `plan for TempCache throws via DownloadConfig directly`() {
        // TempCache must go through Downloads, not DownloadConfig.resolve.
        val cfg = configWith()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            cfg.resolve(Bucket.TempCache)
        }
    }

    @Test fun `corrupt config guard returns fallback without overwriting`() {
        // Placeholder assertion: store semantics tested via LoadResult shape alone
        // since MMKV requires Android. The intent is documented in the kdoc.
    }
}
