package ceui.pixiv.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [StageStore] 纯 JVM 单测 —— 断点续传的全部「安全性判断」都在这层，必须钉死：
 *   - key 由 url 稳定派生（跨重试 / 冷启动可寻回；不同分辨率不串）
 *   - manifest 读写健壮（缺失 / 损坏 → null，不崩）
 *   - Content-Range 解析
 *   - [StageStore.decideWrite] 对 200 / 206 / 416 / 异常码的判定（append vs 重写 vs
 *     已完成 vs 放弃）—— 这是「绝不把全量 body 追加到 partial 尾部」的守门人
 *   - 孤儿 GC 按年龄回收
 */
class StageStoreTest {

    // ---------- keyForUrl ----------

    @Test
    fun `keyForUrl is deterministic and 40 hex chars`() {
        val url = "https://i.pximg.net/img-original/img/2021/01/01/00/00/00/12345_p0.jpg"
        val k1 = StageStore.keyForUrl(url)
        val k2 = StageStore.keyForUrl(url)
        assertEquals("same url must map to same key (cross-retry / cold-start resume)", k1, k2)
        assertEquals("key length must be 40", 40, k1.length)
        assertTrue("key must be lowercase hex", k1.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `keyForUrl differs across urls and resolutions`() {
        val p0 = "https://i.pximg.net/img-original/img/x/12345_p0.jpg"
        val p1 = "https://i.pximg.net/img-original/img/x/12345_p1.jpg"
        // 同一 (illust,page) 不同分辨率是不同 URL → 必须不同 key，否则续传串成损坏图
        val orig = "https://i.pximg.net/img-original/img/x/12345_p0.jpg"
        val large = "https://i.pximg.net/img-master/img/x/12345_p0_master1200.jpg"
        assertNotEquals("different page → different key", StageStore.keyForUrl(p0), StageStore.keyForUrl(p1))
        assertNotEquals("different resolution → different key", StageStore.keyForUrl(orig), StageStore.keyForUrl(large))
    }

    // ---------- Manifest 读写 ----------

    @Test
    fun `manifest round-trips through disk`() {
        withTmpDir { dir ->
            val meta = File(dir, "a.part.meta")
            val m = StageStore.Manifest(
                url = "https://x/y.jpg",
                validator = "\"abc123\"",
                validatorType = StageStore.VALIDATOR_ETAG,
                total = 1024L,
            )
            StageStore.writeManifest(meta, m)
            val read = StageStore.readManifest(meta)
            assertEquals(m, read)
        }
    }

    @Test
    fun `readManifest returns null for missing file`() {
        withTmpDir { dir ->
            assertNull(StageStore.readManifest(File(dir, "nope.part.meta")))
        }
    }

    @Test
    fun `readManifest returns null for corrupt json`() {
        withTmpDir { dir ->
            val meta = File(dir, "bad.part.meta")
            meta.writeText("{ this is not json ]]")
            assertNull(StageStore.readManifest(meta))
        }
    }

    @Test
    fun `readManifest returns null when url field missing`() {
        withTmpDir { dir ->
            val meta = File(dir, "nourl.part.meta")
            meta.writeText("""{"validator":"x","validatorType":"etag","total":10}""")
            assertNull("url is the minimum required field", StageStore.readManifest(meta))
        }
    }

    // ---------- buildManifest：validator 选择 ----------

    @Test
    fun `buildManifest prefers etag over last-modified`() {
        val m = StageStore.buildManifest("u", "\"etag\"", "Wed, 21 Oct 2015 07:28:00 GMT", 100)
        assertEquals(StageStore.VALIDATOR_ETAG, m.validatorType)
        assertEquals("\"etag\"", m.validator)
    }

    @Test
    fun `buildManifest falls back to last-modified`() {
        val lm = "Wed, 21 Oct 2015 07:28:00 GMT"
        val m = StageStore.buildManifest("u", null, lm, 100)
        assertEquals(StageStore.VALIDATOR_LASTMOD, m.validatorType)
        assertEquals(lm, m.validator)
    }

    @Test
    fun `buildManifest none when no validators and ignores blank`() {
        val m = StageStore.buildManifest("u", "   ", "", 100)
        assertEquals(StageStore.VALIDATOR_NONE, m.validatorType)
        assertNull(m.validator)
    }

    // ---------- parseContentRange ----------

    @Test
    fun `parseContentRange parses normal header`() {
        val cr = StageStore.parseContentRange("bytes 200-1023/1024")
        assertEquals(200L, cr!!.start)
        assertEquals(1023L, cr.end)
        assertEquals(1024L, cr.total)
    }

    @Test
    fun `parseContentRange handles star total and case-insensitivity`() {
        val cr = StageStore.parseContentRange("Bytes 500-999/*")
        assertEquals(500L, cr!!.start)
        assertEquals(999L, cr.end)
        assertEquals("unknown total encoded as -1", -1L, cr.total)
    }

    @Test
    fun `parseContentRange handles 416 unsatisfied form bytes star total`() {
        val cr = StageStore.parseContentRange("bytes */1024")
        assertEquals("no concrete start in 416 form", -1L, cr!!.start)
        assertEquals(-1L, cr.end)
        assertEquals(1024L, cr.total)
    }

    @Test
    fun `parseContentRange rejects garbage and null and inverted range`() {
        assertNull(StageStore.parseContentRange(null))
        assertNull(StageStore.parseContentRange(""))
        assertNull(StageStore.parseContentRange("pages 1-2/3"))
        assertNull(StageStore.parseContentRange("bytes 1000-500/2000")) // end < start
    }

    // ---------- decideWrite：核心安全判定 ----------

    @Test
    fun `decideWrite 200 always fresh from zero`() {
        val d = StageStore.decideWrite(200, null, 1024, 500 /*existing ignored*/)
        assertEquals(StageStore.WriteMode.FRESH, d.mode)
        assertEquals(0L, d.startOffset)
        assertEquals(1024L, d.total)
    }

    @Test
    fun `decideWrite 200 unknown content-length keeps total -1`() {
        val d = StageStore.decideWrite(200, null, -1, 0)
        assertEquals(StageStore.WriteMode.FRESH, d.mode)
        assertEquals(-1L, d.total)
    }

    @Test
    fun `decideWrite 206 with matching offset appends`() {
        val d = StageStore.decideWrite(206, "bytes 500-1023/1024", 524, 500)
        assertEquals(StageStore.WriteMode.APPEND, d.mode)
        assertEquals(500L, d.startOffset)
        assertEquals(1024L, d.total)
    }

    @Test
    fun `decideWrite 206 with star total derives total from length`() {
        val d = StageStore.decideWrite(206, "bytes 500-1023/*", 524, 500)
        assertEquals(StageStore.WriteMode.APPEND, d.mode)
        assertEquals("existing + contentLength", 1024L, d.total)
    }

    @Test
    fun `decideWrite 206 with mismatched offset aborts`() {
        // 服务器从别的 offset 开始发 → 接上去会错位 → 必须放弃 partial
        val d = StageStore.decideWrite(206, "bytes 400-1023/1024", 624, 500)
        assertEquals(StageStore.WriteMode.ABORT, d.mode)
    }

    @Test
    fun `decideWrite 206 without content-range aborts`() {
        // 206 却没有可解析的 Content-Range：不敢盲目 append
        val d = StageStore.decideWrite(206, null, 524, 500)
        assertEquals(StageStore.WriteMode.ABORT, d.mode)
    }

    @Test
    fun `decideWrite 416 with complete partial is already-complete`() {
        val d = StageStore.decideWrite(416, "bytes */1024", -1, 1024)
        assertEquals(StageStore.WriteMode.ALREADY_COMPLETE, d.mode)
        assertEquals(1024L, d.total)
    }

    @Test
    fun `decideWrite 416 with mismatched length aborts`() {
        val d = StageStore.decideWrite(416, "bytes */1024", -1, 1000)
        assertEquals(StageStore.WriteMode.ABORT, d.mode)
    }

    @Test
    fun `decideWrite 416 without content-range aborts`() {
        assertEquals(StageStore.WriteMode.ABORT, StageStore.decideWrite(416, null, -1, 1000).mode)
    }

    @Test
    fun `decideWrite other codes abort`() {
        for (code in intArrayOf(301, 403, 404, 500, 503)) {
            assertEquals("code=$code", StageStore.WriteMode.ABORT,
                StageStore.decideWrite(code, null, -1, 0).mode)
        }
    }

    // ---------- clear ----------

    @Test
    fun `clear removes part and meta`() {
        withTmpDir { dir ->
            val key = "deadbeef"
            StageStore.partFile(dir, key).writeBytes(byteArrayOf(1, 2, 3))
            StageStore.metaFile(dir, key).writeText("{}")
            StageStore.clear(dir, key)
            assertFalse(StageStore.partFile(dir, key).exists())
            assertFalse(StageStore.metaFile(dir, key).exists())
        }
    }

    // ---------- sweepOrphans：按年龄回收 ----------

    @Test
    fun `sweepOrphans deletes old part-meta, keeps recent, ignores unrelated`() {
        withTmpDir { dir ->
            val now = 10_000_000_000L
            val maxAge = 1_000_000L

            val oldPart = File(dir, "old.part").apply { writeBytes(ByteArray(4)) }
            val oldMeta = File(dir, "old.part.meta").apply { writeText("{}") }
            val freshPart = File(dir, "fresh.part").apply { writeBytes(ByteArray(4)) }
            val unrelated = File(dir, "keepme.txt").apply { writeText("x") }

            oldPart.setLastModified(now - maxAge - 1)      // 过期
            oldMeta.setLastModified(now - maxAge - 1)      // 过期
            freshPart.setLastModified(now - maxAge / 2)    // 还新（正在下载会被高频顶新）
            unrelated.setLastModified(now - maxAge - 999)  // 过期但非 .part/.meta

            val deleted = StageStore.sweepOrphans(dir, maxAge, now)

            assertEquals("should delete 2 (old .part + old .meta)", 2, deleted)
            assertFalse(oldPart.exists())
            assertFalse(oldMeta.exists())
            assertTrue("recent stage kept", freshPart.exists())
            assertTrue("non-stage file untouched", unrelated.exists())
        }
    }

    @Test
    fun `sweepOrphans on missing dir is a no-op`() {
        assertEquals(0, StageStore.sweepOrphans(File("/no/such/dir/here"), 1000, 5000))
    }

    private inline fun withTmpDir(body: (File) -> Unit) {
        val dir = File.createTempFile("stagestore_", "_d").apply { delete(); mkdirs() }
        try {
            body(dir)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}
