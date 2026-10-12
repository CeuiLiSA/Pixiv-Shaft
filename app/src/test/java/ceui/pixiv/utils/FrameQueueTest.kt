package ceui.pixiv.utils

import android.os.Looper
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * [FrameQueue] 的收益全靠「每帧只跑一个任务」：相邻页放行和信息区 inflate 一旦挤进同一帧，
 * 就回到 PR #1234 之前那条 275ms 的 doFrame。这里把这条约束钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class FrameQueueTest {

    private lateinit var queue: FrameQueue

    @Before
    fun setUp() {
        // 默认模式下一请求帧就立刻出 vsync，一次 idle 会连跑多帧；暂停后 vsync 按 FRAME 间隔到达。
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(FRAME)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        queue = FrameQueue.of(activity.supportFragmentManager)
        // setup() 本身也会跑帧回调，先把它们排空，后面按帧计数才准。
        nextFrame()
    }

    @After
    fun tearDown() {
        ShadowChoreographer.reset()
    }

    @Test
    fun runsOneTaskPerFrame() {
        val ran = mutableListOf<Int>()
        repeat(3) { i -> queue.post { ran += i } }

        assertEquals(emptyList<Int>(), ran)
        nextFrame()
        assertEquals(listOf(0), ran)
        nextFrame()
        assertEquals(listOf(0, 1), ran)
        nextFrame()
        assertEquals(listOf(0, 1, 2), ran)
    }

    @Test
    fun taskPostedFromTaskRunsOnALaterFrame() {
        val ran = mutableListOf<String>()
        queue.post {
            ran += "a"
            queue.post { ran += "c" }
        }
        queue.post { ran += "b" }

        nextFrame()
        assertEquals(listOf("a"), ran)
        nextFrame()
        assertEquals(listOf("a", "b"), ran)
        nextFrame()
        assertEquals(listOf("a", "b", "c"), ran)
    }

    @Test
    fun clearDropsPendingTasksAndQueueStaysUsable() {
        val ran = mutableListOf<Int>()
        queue.post { ran += 1 }
        queue.post { ran += 2 }
        queue.clear()
        nextFrame()
        nextFrame()
        assertEquals(emptyList<Int>(), ran)

        queue.post { ran += 3 }
        nextFrame()
        assertEquals(listOf(3), ran)
    }

    @Test
    fun scopedPerFragmentManager() {
        val fm = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            .supportFragmentManager
        assertSame(FrameQueue.of(fm), FrameQueue.of(fm))
        assertNotSame(queue, FrameQueue.of(fm))
    }

    private fun nextFrame() = shadowOf(Looper.getMainLooper()).idleFor(FRAME)

    private companion object {
        val FRAME: Duration = Duration.ofMillis(16)
    }
}
