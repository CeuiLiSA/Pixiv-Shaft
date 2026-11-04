package ceui.pixiv.snapshot

import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotOriginalCoverageTest {
    @Test
    fun newManifestsDistinguishAllSomeAndNoOriginals() {
        assertEquals(SnapshotOriginalCoverage.ALL, originalCoverage(3, 3, false))
        assertEquals(SnapshotOriginalCoverage.SOME, originalCoverage(1, 3, false))
        assertEquals(SnapshotOriginalCoverage.NONE, originalCoverage(0, 3, true))
    }

    @Test
    fun oldManifestsFallBackAndPromotionPreservesCount() {
        val gson = Gson()
        val old = gson.fromJson(
            """{"snapshotId":"old","illustId":42,"includeOriginal":true,"pageCount":2}""",
            AutoSnapshotManifest::class.java,
        )
        assertEquals(null, old.originalPageCount)
        assertEquals(SnapshotOriginalCoverage.ALL, AutoSnapshotCard(
            AutoSnapshotSummary(old, 0, 0, null)
        ).originalCoverage)

        val partial = old.copy(includeOriginal = false, originalPageCount = 1)
        val promoted = partial.toSnapshotManifest()
        assertEquals(1, promoted.originalPageCount)
        assertEquals(SnapshotOriginalCoverage.SOME, FormalSnapshotCard(
            SnapshotSummary(promoted, 0, 0, null)
        ).originalCoverage)
        assertFalse(autoSnapshotSatisfies(partial, includeComments = false, includeOriginal = true))
        assertTrue(autoSnapshotSatisfies(old, includeComments = false, includeOriginal = true))
    }

    @Test
    fun originalUrlDoesNotFallBackToLargeOrMultiPageCover() {
        val single = Illust(
            id = 1, page_count = 1,
            image_urls = ImageUrls(large = "large"),
            meta_single_page = MetaSinglePage(original_image_url = "original"),
        )
        assertEquals("original", single.snapshotOriginalPageUrl(0))
        assertEquals(null, single.copy(meta_single_page = null).snapshotOriginalPageUrl(0))
        assertEquals("fallback", single.copy(
            meta_single_page = MetaSinglePage(original_image_url = " "),
            image_urls = ImageUrls(original = "fallback"),
        ).snapshotOriginalPageUrl(0))

        val multi = Illust(
            id = 2, page_count = 2,
            image_urls = ImageUrls(original = "cover"),
            meta_pages = listOf(
                MetaPage(ImageUrls(original = "page0")),
                MetaPage(ImageUrls(large = "page1large")),
            ),
        )
        assertEquals("page0", multi.snapshotOriginalPageUrl(0))
        assertEquals(null, multi.snapshotOriginalPageUrl(1))
    }
}