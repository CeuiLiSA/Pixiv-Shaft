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

    @Test
    fun `time spent suspended does not count towards the dwell`() {
        val visit = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)

        visit.suspend(41_000L)
        visit.resume(101_000L)

        assertEquals(70_000L, visit.finish(131_000L))
    }

    @Test
    fun `suspending or resuming twice is a no-op`() {
        val visit = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)

        visit.suspend(11_000L)
        visit.suspend(41_000L)
        visit.resume(41_000L)
        visit.resume(51_000L)

        // 10s（1→11）+ 20s（41→61），挂起中的 30s 不计；重复的 suspend / resume 不重复累加。
        assertEquals(30_000L, visit.finish(61_000L))
    }

    @Test
    fun `finishing while suspended settles only the accumulated part`() {
        val visit = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)

        visit.suspend(41_000L)

        assertEquals(40_000L, visit.finish(91_000L))
    }

    @Test
    fun `suspend and resume after finish are ignored`() {
        val visit = AutoSnapshotEngine.ArtworkVisit(42L, 1_000L)
        assertEquals(10_000L, visit.finish(11_000L))

        visit.suspend(20_000L)
        visit.resume(30_000L)

        assertNull(visit.finish(40_000L))
    }
}
