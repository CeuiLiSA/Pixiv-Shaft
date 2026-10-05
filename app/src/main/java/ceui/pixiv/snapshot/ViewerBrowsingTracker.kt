package ceui.pixiv.snapshot

/**
 * 二级大图会话的浏览观测：按页累计驻留、记录每页是否离开过初始缩放，会话结束时产出
 * 「一页一份」的样本（[AutoSnapshotViewerPageSample]）。
 *
 * **只观测，不做任何消费**：不参与触发、评分或淘汰判定。
 *
 * 计时语义与 [AutoSnapshotEngine.ArtworkVisit] 对齐：宿主可见才走表；不可见（切后台 /
 * 被上层不透明页压住）时 [suspend] 停表，回来 [resume] 接着算同一段，挂起期间不计入任何页。
 *
 * 线程模型：所有方法都只在主线程调用（Activity / Fragment 生命周期与 ViewPager 回调都在主线程），
 * 内部状态不加锁。
 */
internal class ViewerBrowsingTracker(private val pageCount: Int) {

    private val dwellByPage = HashMap<Int, Long>()
    private val zoomedPages = HashSet<Int>()

    private var currentPage = -1
    private var runningSince = 0L
    private var running = false

    /** 宿主可见（onStart）：从此刻起给当前页走表。重复调用是空操作。 */
    fun resume(now: Long) {
        if (running) return
        running = true
        runningSince = now
    }

    /** 宿主不可见（onStop）：把当前页已走的一段结掉，停表。重复调用是空操作。 */
    fun suspend(now: Long) {
        if (!running) return
        accumulate(now)
        running = false
    }

    /** 当前显示的页发生变化（ViewPager onPageSelected / 初始页）。同页重复调用是空操作。 */
    fun onPageVisible(page: Int, now: Long) {
        if (page == currentPage) return
        if (running) accumulate(now)
        currentPage = page
        runningSince = now
    }

    /** 某页被观察到离开初始缩放；幂等，重复调用无副作用。 */
    fun onPageZoomed(page: Int) {
        if (page >= 0) zoomedPages += page
    }

    /**
     * 会话结束：结掉当前页，产出逐页样本（按页号升序），驻留为 0 或越界的页不产出。
     *
     * @param nowElapsed 单调时钟读数（SystemClock.elapsedRealtime()），只用来结算最后一段驻留；
     * @param at 墙钟时间（System.currentTimeMillis()），写入样本 [AutoSnapshotViewerPageSample.at]，
     *   供行为库按时间窗口裁剪 —— 两个时钟刻意分开，避免墙钟跳变污染驻留时长。
     */
    fun finish(nowElapsed: Long, at: Long): List<AutoSnapshotViewerPageSample> {
        if (running) {
            accumulate(nowElapsed)
            running = false
        }
        currentPage = -1
        return dwellByPage.entries
            .filter { it.value > 0L && it.key in 0 until pageCount }
            .sortedBy { it.key }
            .map {
                AutoSnapshotViewerPageSample(
                    at = at,
                    page = it.key,
                    ms = it.value,
                    zoomed = it.key in zoomedPages,
                )
            }
    }

    /** 把当前页从 [runningSince] 到 [now] 的一段累加进该页；无当前页时只推进游标。 */
    private fun accumulate(now: Long) {
        val page = currentPage
        if (page < 0) {
            runningSince = now
            return
        }
        val delta = (now - runningSince).coerceAtLeast(0L)
        if (delta > 0L) dwellByPage[page] = (dwellByPage[page] ?: 0L) + delta
        runningSince = now
    }
}