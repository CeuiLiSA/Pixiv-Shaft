package ceui.pixiv.utils

import android.view.Choreographer
import androidx.fragment.app.FragmentManager
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * 「每帧只做一件事」的分帧队列，按 [FragmentManager] 划分作用域。
 *
 * ### 为什么需要它
 *
 * 详情页首帧不能把 cur-1 / cur / cur+1 三页都建出来（实测那一条 doFrame 435ms / 54 帧），
 * 所以相邻页先停在 `CREATED`、首帧之后再逐帧放行（见 `LazyFragmentStatePagerAdapter`）；
 * 而每页的信息区（`second_linear` ViewStub，21 个 View）是另一块同样不能压进同一帧的活。
 *
 * 两处各自用 `postOnAnimation` 调度的话，会**撞进同一帧的 ANIMATION 阶段** —— 实测出现过
 * 两件事叠在一条消息里（275ms，报 Skipped 34 帧）。所以统一到一个队列：入队任务按 FIFO
 * **每帧只跑一个**，跑完自动排下一帧，两块活自然错开。
 *
 * ### 作用域
 *
 * 用 [FragmentManager] 作 key：`Fragment.parentFragmentManager` 与宿主 Activity 的
 * `supportFragmentManager` 在同一页面里是同一个对象，于是页内所有分帧任务共享一个队列；
 * 页面销毁后 FragmentManager 被回收，队列（WeakHashMap 的 key）随之消失。
 *
 * ⚠️ 队列不保证任务一定被执行 —— 页面销毁时 [clear] 会丢掉未跑的。所以任务本身要能容忍
 * 「视图已经没了」这种情况（例如检查 `view != null`）。
 */
class FrameQueue private constructor() {

    private val tasks = ArrayDeque<Runnable>()
    private var scheduled = false

    private val step = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            tasks.poll()?.run()
            scheduleNext()
        }
    }

    /**
     * 入队。队列空闲时会在**下一帧的 ANIMATION 阶段**开始逐帧执行，每帧只跑一个任务。
     *
     * 任务里可以再 [post]，会排到队列尾部（也就是之后的某一帧）。
     */
    fun post(task: Runnable) {
        tasks.add(task)
        scheduleNext()
    }

    /** 丢弃还没执行的任务。页面销毁时调，避免任务在已经 detach 的视图上跑。 */
    fun clear() {
        tasks.clear()
    }

    private fun scheduleNext() {
        if (scheduled || tasks.isEmpty()) return
        scheduled = true
        // postFrameCallback（公开 API）的回调就落在 CALLBACK_ANIMATION 阶段，正是要的位置。
        // 不要用 Choreographer.postCallback —— 那个是 @hide。
        Choreographer.getInstance().postFrameCallback(step)
    }

    companion object {
        private val queues = WeakHashMap<FragmentManager, FrameQueue>()

        /**
         * 取某个页面（[FragmentManager]）的分帧队列。
         *
         * 只在主线程调用（[Choreographer] 的要求）。
         */
        @JvmStatic
        fun of(fm: FragmentManager): FrameQueue = synchronized(queues) {
            queues.getOrPut(fm) { FrameQueue() }
        }
    }
}
