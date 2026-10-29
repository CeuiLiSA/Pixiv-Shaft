package ceui.pixiv.snapshot

import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.RetryScope
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SnapshotBackfillActionsTest {

    @Test
    fun `page split skips works with a formal snapshot and drops duplicates and bad ids`() {
        val split = splitBackfillPage(listOf(1L, 2L, 3L, 2L, 0L, -5L, 4L)) { it == 3L }

        assertEquals(listOf(1L, 2L, 4L), split.toQueue)
        assertEquals(1, split.existing)
    }

    @Test
    fun `auto snapshot is promoted only when it covers what this queue asks for`() {
        val regular = AutoSnapshotManifest(snapshotId = "a", illustId = 1L, includeOriginal = false)
        val original = regular.copy(includeOriginal = true)

        assertTrue(autoSnapshotSatisfies(regular, includeComments = false, includeOriginal = false))
        assertTrue(autoSnapshotSatisfies(original, includeComments = false, includeOriginal = false))
        assertTrue(autoSnapshotSatisfies(original, includeComments = false, includeOriginal = true))
        // 自动快照不带原图 / 从不带评论：要这些就只能重新生成。
        assertFalse(autoSnapshotSatisfies(regular, includeComments = false, includeOriginal = true))
        assertFalse(autoSnapshotSatisfies(original, includeComments = true, includeOriginal = false))
    }

    @Test
    fun `image download failure wrapped in SnapshotException is retried as a network error`() {
        val outcome = SnapshotException("image failed", IOException("reset")).toBackfillOutcome(isOnline = true)

        assertTrue(outcome is ActionOutcome.Retry)
        assertEquals(RetryScope.ACTION, (outcome as ActionOutcome.Retry).scope)
    }

    @Test
    fun `rate limit anywhere in the cause chain cools the whole queue`() {
        val http = HttpException(Response.error<Any>(429, "".toResponseBody()))
        val outcome = SnapshotException("comments failed", http).toBackfillOutcome(isOnline = true)

        assertTrue(outcome is ActionOutcome.Retry)
        assertEquals(RetryScope.QUEUE, (outcome as ActionOutcome.Retry).scope)
    }

    @Test
    fun `local failures without a network cause are terminal`() {
        val outcome = SnapshotException("page url missing").toBackfillOutcome(isOnline = true)

        assertTrue(outcome is ActionOutcome.Fail)
    }

    @Test
    fun `progress counts skipped and failed works as settled`() {
        val run = SnapshotBackfillRun(
            runId = "r", owner = 1L, startedAt = 0L, scopeName = "ALL",
            includeComments = false, includeOriginal = false, wifiOnly = true,
            discovered = 100, existing = 30, done = 40, unavailable = 2, failed = 3, scanChains = 1,
        )

        assertEquals(75, run.settled)
        assertEquals(25, run.remaining)
        assertTrue(run.isScanning)
        assertFalse(run.copy(finishedAt = 1L).isScanning)
    }
}
