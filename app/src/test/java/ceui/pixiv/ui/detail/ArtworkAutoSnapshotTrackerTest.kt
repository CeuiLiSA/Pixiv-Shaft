package ceui.pixiv.ui.detail

import android.app.Application
import android.os.Bundle
import ceui.pixiv.snapshot.AutoSnapshotEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页自动快照计时凭证的生命周期规则。这些规则只有真机旋屏 / 横滑才碰得到，
 * 回归了也不会崩，只会悄悄多触发或漏触发自动快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtworkAutoSnapshotTrackerTest {

    private class FakeEngine : ArtworkAutoSnapshotTracker.Engine {
        val calls = mutableListOf<String>()

        override fun visible(illustId: Long, type: String?, pageCount: Int, countAsEntry: Boolean) =
            AutoSnapshotEngine.ArtworkVisit(illustId, 0L).also { calls += "visible(entry=$countAsEntry)" }

        override fun suspended(visit: AutoSnapshotEngine.ArtworkVisit) {
            calls += "suspended"
        }

        override fun resumed(visit: AutoSnapshotEngine.ArtworkVisit) {
            calls += "resumed"
        }

        override fun left(visit: AutoSnapshotEngine.ArtworkVisit, evaluate: Boolean) {
            calls += "left(evaluate=$evaluate)"
        }
    }

    private val engine = FakeEngine()
    private val tracker = ArtworkAutoSnapshotTracker(engine)

    @Test
    fun `进二级大图页再回来不重开表也不重复计进入`() {
        tracker.onResume(1L, null)
        tracker.onPause(hostStillResumed = false) // 宿主自己先 paused:被半透明层盖住
        tracker.onResume(1L, null)
        assertEquals(listOf("visible(entry=true)"), engine.calls)
    }

    @Test
    fun `切后台结算并评估, 回来重开表但不再计进入`() {
        tracker.onResume(1L, null)
        tracker.onPause(hostStillResumed = false)
        tracker.onStop(changingConfigurations = false)
        tracker.onResume(1L, null)
        assertEquals(
            listOf("visible(entry=true)", "left(evaluate=true)", "visible(entry=false)"),
            engine.calls,
        )
    }

    @Test
    fun `旋屏只结算不评估, 重建后的新实例也不再计进入`() {
        tracker.onResume(1L, null)
        tracker.onPause(hostStillResumed = false)
        tracker.onStop(changingConfigurations = true)
        val saved = Bundle().also { tracker.save(it) }

        val rebuilt = ArtworkAutoSnapshotTracker(engine).apply { restore(saved) }
        rebuilt.onResume(1L, null)
        assertEquals(
            listOf("visible(entry=true)", "left(evaluate=false)", "visible(entry=false)"),
            engine.calls,
        )
    }

    @Test
    fun `横滑降级的页旋屏照样评估`() {
        tracker.onResume(1L, null)
        tracker.onPause(hostStillResumed = true) // 宿主还 RESUMED:本页被横滑降级
        tracker.onStop(changingConfigurations = true)
        assertEquals(
            listOf("visible(entry=true)", "suspended", "left(evaluate=true)"),
            engine.calls,
        )
    }

    @Test
    fun `滑回来后降级标记清掉, 旋屏回到只结算`() {
        tracker.onResume(1L, null)
        tracker.onPause(hostStillResumed = true)
        tracker.onResume(1L, null)
        tracker.onStop(changingConfigurations = true)
        assertEquals("left(evaluate=false)", engine.calls.last())
    }

    @Test
    fun `凭证只结算一次, onStop 之后的 onDestroyView 是空操作`() {
        tracker.onResume(1L, null)
        tracker.onStop(changingConfigurations = false)
        tracker.onDestroyView()
        assertEquals(1, engine.calls.count { it.startsWith("left") })
    }

    @Test
    fun `视口同步只在 RESUMED 时生效, 且没有凭证时不去遍历视口`() {
        var probed = 0
        tracker.syncViewport(isResumed = true) { probed++; true }
        assertEquals(0, probed)

        tracker.onResume(1L, null)
        tracker.syncViewport(isResumed = false) { probed++; false }
        tracker.syncViewport(isResumed = true) { probed++; false }
        tracker.syncViewport(isResumed = true) { probed++; true }
        assertEquals(2, probed)
        assertEquals(listOf("visible(entry=true)", "suspended", "resumed"), engine.calls)
    }
}
