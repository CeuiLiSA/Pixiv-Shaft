package ceui.pixiv.ui.bulk

import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.pixiv.actions.PixivActionQueue
import ceui.pixiv.actions.PixivActions
import com.hjq.toast.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.max

/**
 * **唯一调用方**：[BulkSelectV3Fragment] 底栏「收藏动作」菜单（issue #974）。
 *
 * 把勾选的作品逐个交给 [PixivActions.setIllustBookmark] —— 也就是走和单张爱心
 * **完全同一条**路径：乐观更新写进 [ceui.loxia.ObjectPool] 的每个表示 + 发跨列表广播，
 * 请求进 `:actionqueue` 限流队列串行发送，撞 429 整队冷却，进程被杀下次启动接着发，
 * 终态失败由队列回滚。批量在这里**不另起一条写路径**：仓库里原本就是「三套并行的收藏写法
 * 行为各不相同」，`PixivActions` 这个门面正是为了消灭那类不一致才存在的。
 *
 * 因此这里只负责三件门面管不了的事：
 *  - **过滤掉已经是目标态的项**，好让 toast 里的数字等于真正会发出去的请求数
 *    （门面自己会静默 early-return，不过滤的话「已加入队列 500 项」可能实际只发了 3 条）；
 *  - **分块 + [yield]**，别在一个主线程 tick 里跑完几千次 ObjectPool 写入 + 广播；
 *  - **用进程级 scope**，调用方入队后立刻 `finish()`，挂在 fragment 上的协程会被连坐取消，
 *    剩下的项就静默丢了（对齐 [LegacyBatchEnqueue] 的同一考量）。
 */
internal object BulkBookmarkEnqueue {

    /**
     * 必须是 Main：[PixivActions] 最终写的是 [ceui.loxia.ObjectPool] 里的
     * `MutableLiveData.setValue`，子线程调会抛。`immediate` 让首块在调用线程上同步跑完，
     * 用户点确认后眼前那一屏立刻就是新状态。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 每块之间让一次主线程。每项都要写两三个 ObjectPool 表示再发一条
     * LocalBroadcast，几千项连着跑完足够掉帧；50 是「一块的开销小到看不出来」的量级。
     */
    private const val CHUNK = 50

    /** 勾选项里真正需要改状态的那些。UI 拿它算数量、算耗时，别自己再数一遍。 */
    fun pendingCount(illusts: List<IllustsBean>, bookmark: Boolean): Int =
        illusts.count { it.isIs_bookmarked != bookmark }

    /**
     * 把 [illusts] 中还不是目标态的项全部入队。立即返回。
     *
     * @param bookmark true = 收藏，false = 取消收藏
     * @param restrict 仅 [bookmark] 为 true 时有意义，见 [PixivActions.defaultBookmarkRestrict]
     */
    fun enqueue(illusts: List<IllustsBean>, bookmark: Boolean, restrict: String) {
        val todo = illusts.filter { it.isIs_bookmarked != bookmark }
        if (todo.isEmpty()) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val ctx = Shaft.getContext()
        if (ctx != null) {
            val template =
                if (bookmark) R.string.bulk_bookmark_enqueued else R.string.bulk_unbookmark_enqueued
            Toaster.showShort(ctx.getString(template, todo.size))
        }
        scope.launch {
            todo.chunked(CHUNK).forEach { chunk ->
                chunk.forEach { PixivActions.setIllustBookmark(it, bookmark, restrict) }
                yield()
            }
        }
    }

    /**
     * 全部发完大概要几分钟 —— 队列是串行 + 最小间隔的，[count] 上百时是十几分钟起步。
     * 向上取整且至少 1，免得确认框上写着「约 0 分钟」。
     */
    fun estimatedMinutes(count: Int): Int {
        val totalMs = count.toLong() * PixivActionQueue.MIN_GAP_MS
        return max(1L, (totalMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    }

    private const val MS_PER_MINUTE = 60_000L
}
