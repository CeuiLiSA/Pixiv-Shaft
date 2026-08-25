package ceui.lisa.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [Manager] 的 reactive 门面：把 Manager.java 命令式的 mutation 翻成 Flow 推送。
 *
 * # 为什么单独搞一个文件
 *
 * Manager 是 Java 类，直接在它里面塞 `MutableSharedFlow` 字段 + 在每个
 * mutation 点写 kotlinx.coroutines API 既丑又容易写错。把 reactive 复杂度
 * 整体收到这个 Kotlin object 里：
 *   - Java 端只多一行 `ManagerReactive.invalidate()`，看起来就是普通方法调用
 *   - Flow / Channel / dispatcher 等概念全在 Kotlin 侧
 *   - 单一数据源仍是 [Manager.content]；这边只是"哪儿脏了"信号 + 取快照
 *
 * # 设计
 *
 * - [invalidations] 是 `replay=1 + DROP_OLDEST` 的 [MutableSharedFlow]，
 *   扮演脏标记：任何 mutation `tryEmit(Unit)` 一下。
 *   - replay=1：新订阅者立刻收到一个 tick（不用等下一次 mutation）
 *   - DROP_OLDEST + 0 buffer：高频 invalidate（progress 1% 一次，5 并发
 *     就 ~500/s）会被合并成"最多 1 个待处理 tick"，天然 conflate；不会
 *     爆 buffer 也不会阻塞 producer
 * - [contentFlow] = invalidations.map { Manager.contentSnapshot() }
 *   - 每个 tick 从 Manager 拉当前快照（synchronized + 浅拷贝）
 *   - DownloadItem 仍是同一份对象引用（aliasing），所以 collector 端要拍
 *     成 immutable snapshot 再喂 DiffUtil（active tab 已用 ActiveSnapshot
 *     处理）
 * - [activeCountFlow]：派生计数 + distinctUntilChanged，让"N 正在"这种
 *   行号只在数字真的变了时才 emit
 *
 * # 用法
 *
 * Manager.java 端：
 *   - 任何修改 content 列表 / DownloadItem state / nonius / paused 的地方
 *     末尾加 `ManagerReactive.invalidate();`
 *   - 暴露 `public List<DownloadItem> contentSnapshot()` 给 [contentFlow] 用
 *
 * UI 端：
 *   ```
 *   ManagerReactive.contentFlow
 *       .collect { items -> ... }    // items 是 Manager.content 当前快照
 *   ```
 *   不需要 polling，不需要 broadcast receiver 兜底。
 */
object ManagerReactive {

    private val invalidations = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply {
        // 初始 tick：让首个 collector 立刻拿到一次 snapshot，不用等 mutation。
        tryEmit(Unit)
    }

    /**
     * Manager 任意 mutation（add / remove / state change / progress）后调一次。
     * tryEmit 线程安全 + 非阻塞，可以从主线程 / IO 线程 / 下载线程池
     * 任意位置调。
     */
    @JvmStatic
    fun invalidate() {
        invalidations.tryEmit(Unit)
    }

    /**
     * UI collect 这个就够。每次 invalidate 后 emit 当前 [Manager.content]
     * 的浅拷贝快照（list 是新对象，元素是原 DownloadItem 引用）。
     */
    val contentFlow: Flow<List<DownloadItem>> = invalidations
        .map { Manager.get().contentSnapshot() }

    /**
     * 派生：当前正在传输的 page 数。distinctUntilChanged 让 collector 只在
     * 数字真的变了时更新，避免 progress 高频 invalidate 时上层无谓 rebind。
     */
    val activeCountFlow: Flow<Int> = contentFlow
        .map { items -> items.count { it.state == DownloadItem.DownloadState.DOWNLOADING } }
        .distinctUntilChanged()

    /**
     * `illust_download_table`（"已完成" tab 数据源）的脏标记。每个 page 成功
     * 后 Manager 写一行，调用 [pokeDoneTable]。已完成 tab UI 端 collect 这个
     * tick 后用 suspend `dao.getAll(...)` 拿最新行；用户手动删除单条 / 清空
     * 整个 tab 时也要 poke 一次。
     *
     * 为什么不直接用 Room 的 `dao.flowAll`：跟 download_queue 同样的 bug，
     * Room InvalidationTracker 在快速连续 INSERT 序列下首次 emit 后静默不再
     * re-emit，导致已完成 tab 卡在初始状态。
     *
     * replay=1 + DROP_OLDEST：新 collector 立刻拿一帧；高频 tick 自动合并。
     */
    val doneTableInvalidations = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    /** Java 端调：Manager.java 写 illust_download_table / 删行后 poke。 */
    @JvmStatic
    fun pokeDoneTable() {
        doneTableInvalidations.tryEmit(Unit)
    }
}
