package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSnapshotVisitTest {
    @Test
    fun `two pages of the same artwork keep independent dwell times`() {
        val first = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)
        val second = AutoSnapshotEngine.ArtworkVisit(42L, 31_000L)

        assertEquals(60_000L, first.finish(61_000L))
        assertEquals(30_000L, second.finish(61_000L))
    }

    @Test
    fun `a hidden page consumes its visit even if recording is skipped`() {
        val visit = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)
        assertEquals(0L, visit.finish(1_000L))
        assertNull(visit.finish(61_000L))
    }
}
