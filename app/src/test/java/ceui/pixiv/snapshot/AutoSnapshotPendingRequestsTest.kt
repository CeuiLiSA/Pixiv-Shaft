package ceui.pixiv.snapshot

import ceui.pixiv.api.model.Illust
import org.junit.Assert.*
import org.junit.Test

class AutoSnapshotPendingRequestsTest {
    @Test
    fun `bookmark received behind behavior survives disabling behavior while queued`() {
        val pending = AutoSnapshotPendingRequests(32)
        val original = Illust(id = 42L, is_bookmarked = false)
        val request = checkNotNull(pending.add(original, "revisit"))
        val confirmed = original.copy(is_bookmarked = true)
        assertNull(pending.add(confirmed, null))

        val ready = checkNotNull(pending.ready(request, bookmarkEnabled = true, behaviorEnabled = false))
        assertSame(confirmed, ready.illust)
        assertNull(ready.behaviorSignal)
    }

    @Test
    fun `behavior received behind bookmark survives disabling bookmark while queued`() {
        val pending = AutoSnapshotPendingRequests(32)
        val illust = Illust(id = 42L)
        val request = checkNotNull(pending.add(illust, null))
        assertNull(pending.add(illust, "dwell"))

        assertEquals("dwell", pending.ready(request, bookmarkEnabled = false, behaviorEnabled = true)?.behaviorSignal)
    }

    @Test
    fun `full queue still merges same artwork without admitting another work`() {
        val pending = AutoSnapshotPendingRequests(1)
        val illust = Illust(id = 42L)
        val request = checkNotNull(pending.add(illust, "revisit"))
        assertNull(pending.add(Illust(id = 43L), null))
        assertNull(pending.add(illust, null))
        assertNotNull(pending.ready(request, bookmarkEnabled = true, behaviorEnabled = false))
        pending.finish(request)
        assertNotNull(pending.add(Illust(id = 43L), null))
    }

    @Test
    fun `an enabled setting cannot authorize a trigger that was never received`() {
        val pending = AutoSnapshotPendingRequests(32)
        val behavior = checkNotNull(pending.add(Illust(id = 42L), "dwell"))
        assertNull(pending.ready(behavior, bookmarkEnabled = true, behaviorEnabled = false))
        val bookmark = checkNotNull(pending.add(Illust(id = 43L), null))
        assertNull(pending.ready(bookmark, bookmarkEnabled = false, behaviorEnabled = true))
    }

    @Test
    fun `skipped worker cleanup cannot delete a newer request for the same artwork`() {
        val pending = AutoSnapshotPendingRequests(1)
        val illust = Illust(id = 42L)
        val old = checkNotNull(pending.add(illust, "revisit"))
        assertNull(pending.ready(old, bookmarkEnabled = false, behaviorEnabled = false))
        val replacement = checkNotNull(pending.add(illust, null))

        pending.finish(old)
        assertNotNull(pending.ready(replacement, bookmarkEnabled = true, behaviorEnabled = false))
        assertNull(pending.add(Illust(id = 43L), null))
    }
}
