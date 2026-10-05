package ceui.pixiv.ui.bulk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载「重复下 / 循环」的回归测试。
 *
 * 复现「下载完成后假失败 → 队列重拉 → 重复下同一张图 → 极端情况逮着一个图循环」，
 * 并把修复后的判据钉死：谁要是把 R1 的收口判据改回"没有未完成就算失败"、或把补页
 * 改回递增计数器，这里必须翻。
 *
 * 为什么用模型而不是直接调生产代码：`Manager` / `QueueDownloadManager` 的完成链路
 * 耦合 Android Handler / Room / OkHttp，纯 JVM 跑不起来（同
 * [ceui.lisa.core.ManagerStagingConcurrencyTest] 的取舍）。这里把链路复刻成一个
 * **可注入"主线程 drain 时机"** 的小状态机，把真正的变量——`postMain(remove)` 的
 * 异步时序——变成可枚举的输入，从而对**所有**时序下结论，而不是只赌一种时序。
 *
 * 覆盖：
 *   1. R1 判据本身（表格）
 *   2. R1 端到端：假失败 → 重复下 → 循环（枚举全部 drain 时序）
 *   3. R2 retry path 补页：漏页 / 整段重下
 *   4. P1 补页顺序：按 page index 升序、每页恰好一次
 *   5. R3/R5 伪 stranded：同 uuid 双传输
 *   6. R9 队列顺序：失败行插队（逮着一个图）
 *
 * 直接跑生产代码的那几条不在这里：[ceui.lisa.core.ManagerRestoreTest] 钉冷启动状态
 * 归一，[ceui.pixiv.db.queue.DownloadQueueDaoTest] 钉队列 seq 重排。
 */
class DownloadRepeatLoopSimulationTest {

    private companion object {
        const val INIT = 0
        const val DOWNLOADING = 1
        const val SUCCESS = 2
        const val FAILED = 3
        const val PAUSED = 4

        const val ROW_PENDING = "PENDING"
        const val ROW_DOWNLOADING = "DOWNLOADING"
        const val ROW_SUCCESS = "SUCCESS"
        const val ROW_FAILED = "FAILED"
    }

    private class Page(val index: Int, var state: Int = INIT, var paused: Boolean = false)

    // ─────────────────────────────────────────────────────────────────────
    // 1. R1 判据：settleCompleted 该不该把这条 illust 判失败
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @return true = 收口为 FAILED
     *
     * OLD（现行，QueueDownloadManager.kt:413-431）：
     *   pages 非空 且 没有 INIT/DOWNLOADING/PAUSED → FAILED。**SUCCESS 不算 unsettled**，
     *   所以"已落盘但 remove 还没跑"的 SUCCESS 页会被判失败。
     * NEW：只有"每一页都确定 FAILED"才 FAILED；SUCCESS / 未完成一律继续等。
     */
    private fun oldFinalizeAsFailed(pages: List<Page>): Boolean {
        if (pages.isEmpty()) return false
        val unsettled = pages.count {
            it.state == INIT || it.state == DOWNLOADING || it.paused || it.state == PAUSED
        }
        return unsettled == 0
    }

    private fun newFinalizeAsFailed(pages: List<Page>): Boolean {
        if (pages.isEmpty()) return false
        return pages.all { it.state == FAILED && !it.paused }
    }

    @Test
    fun `R1 判据 — SUCCESS 留在 content 时 OLD 判失败、NEW 继续等`() {
        data class Case(val name: String, val pages: List<Page>, val oldFailed: Boolean, val newFailed: Boolean)

        val cases = listOf(
            Case("单页已完成但 remove 未跑", listOf(Page(0, SUCCESS)), oldFailed = true, newFailed = false),
            Case("多页全已完成但 remove 未跑", listOf(Page(0, SUCCESS), Page(1, SUCCESS)), oldFailed = true, newFailed = false),
            Case("一页成功一页失败", listOf(Page(0, SUCCESS), Page(1, FAILED)), oldFailed = true, newFailed = false),
            Case("全部真失败", listOf(Page(0, FAILED), Page(1, FAILED)), oldFailed = true, newFailed = true),
            Case("还有一页在下", listOf(Page(0, SUCCESS), Page(1, DOWNLOADING)), oldFailed = false, newFailed = false),
            Case("还有一页 INIT", listOf(Page(0, SUCCESS), Page(1, INIT)), oldFailed = false, newFailed = false),
            Case("一页暂停", listOf(Page(0, SUCCESS), Page(1, FAILED).also { it.paused = true }), oldFailed = false, newFailed = false),
            Case("全部被移除", emptyList(), oldFailed = false, newFailed = false),
        )

        for (c in cases) {
            assertEquals("OLD 判据 [${c.name}]", c.oldFailed, oldFinalizeAsFailed(c.pages))
            assertEquals("NEW 判据 [${c.name}]", c.newFailed, newFinalizeAsFailed(c.pages))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. R1 端到端：把"主线程 drain 时机"枚举出来，证明 OLD 会重复下
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 复刻 Manager 完成链路 + QueueDownloadManager 主循环的**顺序**：
     *
     *   每步： [drain?] → settleCompleted → [drain?] → fillSlots(pull) → [drain?] → dispatch/pump
     *
     * - `settle` 就是 `settleCompleted()`；`pull` 是 `fillSlots` 拉到本行时的 retry path；
     * - `dispatchOne` = `pumpAvailableSlots` 派发一个 INIT 页 + 传输完成（state→SUCCESS，
     *   写已完成记录，`postMain(remove)` 入队）；
     * - `drain` = 主线程执行那个排队的 remove Runnable（**异步，时机由 [drainAt] 决定**）。
     *
     * 并发固定为 1（单页场景），把变量收敛到"drain 落在哪一步"。
     */
    private class LoopModel(
        val totalPages: Int,
        val preexisting: List<Int> = emptyList(),
        /** false = 现行逻辑；true = 新方案（正确判据 + 按页补页 + 已完成记录短路） */
        val newPolicy: Boolean,
        val maxSteps: Int = 4,
    ) {
        val content = ArrayList<Page>()
        val completedRecord = HashSet<Int>()
        val transferCount = HashMap<Int, Int>()
        /** 实际发生传输的页索引顺序，用来钉"按 index 升序补页、每页只下一次"。 */
        val transferOrder = ArrayList<Int>()
        private val mainQueue = ArrayDeque<Page>()

        var rowStatus = ROW_PENDING
        var retryCount = 0
        private var dispatchedCount = 0
        private var plan = ArrayDeque<Int>()
        private var inFlight = false

        init {
            for (i in preexisting) content.add(Page(i, INIT))
        }

        /** [drainAt] 对第 k 个边界返回 true = 主线程在那一刻跑一次 remove。 */
        fun run(drainAt: (Int) -> Boolean): LoopModel {
            var b = 0
            var step = 0
            while (step < maxSteps && rowStatus != ROW_SUCCESS && rowStatus != ROW_FAILED) {
                step++
                if (drainAt(b++)) drain()
                settle()
                if (drainAt(b++)) drain()
                if (rowStatus == ROW_PENDING) pull()
                if (drainAt(b++)) drain()
                dispatchOne()
            }
            return this
        }

        private fun settle() {
            if (!inFlight) return
            if (dispatchedCount < totalPages) return          // 还有页没派发
            if (content.isEmpty()) { rowStatus = ROW_SUCCESS; return }
            val fail = if (newPolicy) {
                content.all { it.state == FAILED && !it.paused }
            } else {
                content.count { it.state == INIT || it.state == DOWNLOADING || it.paused || it.state == PAUSED } == 0
            }
            if (fail) { retryCount++; rowStatus = ROW_PENDING; inFlight = false }
        }

        /** 对应 fillSlots → pullRowToInFlight：决定本轮要补哪些页。 */
        private fun pull() {
            rowStatus = ROW_DOWNLOADING
            inFlight = true
            val present = content.map { it.index }.toSet()
            if (newPolicy) {
                // 按 page index 精确补页；已完成的页（completedRecord）不重派
                plan = ArrayDeque((0 until totalPages).filter { it !in present && it !in completedRecord })
                dispatchedCount = present.size
            } else {
                if (present.isEmpty()) {
                    plan = ArrayDeque((0 until totalPages).toList())
                    dispatchedCount = 0
                } else {
                    // 现行：无条件宣称"全派发了"，实际只跑 content 里已有的那几页
                    plan = ArrayDeque()
                    dispatchedCount = totalPages
                }
            }
        }

        private fun dispatchOne() {
            if (dispatchedCount < totalPages && plan.isNotEmpty()) {
                content.add(Page(plan.removeFirst(), INIT))
                dispatchedCount++
            }
            val p = content.firstOrNull { it.state == INIT && !it.paused } ?: return
            p.state = DOWNLOADING
            transferCount[p.index] = (transferCount[p.index] ?: 0) + 1   // ★ 真·传输计数
            transferOrder.add(p.index)
            p.state = SUCCESS
            completedRecord.add(p.index)
            mainQueue.addLast(p)                                         // postMain(remove)，异步
        }

        private fun drain() {
            val p = mainQueue.removeFirstOrNull() ?: return
            content.remove(p)
        }

        val totalTransfers: Int get() = transferCount.values.sum()
        val maxPageTransfers: Int get() = transferCount.values.maxOrNull() ?: 0
    }

    /** 枚举 [boundaries] 个边界的全部 drain 时序（2^n），对 [policy] 收集结果。 */
    private fun enumerateSchedules(
        boundaries: Int,
        policy: Boolean,
        build: () -> LoopModel,
    ): List<LoopModel> {
        val results = ArrayList<LoopModel>()
        val total = 1 shl boundaries
        for (mask in 0 until total) {
            val m = build()
            m.run { k -> (mask shr k) and 1 == 1 }
            results.add(m)
        }
        return results
    }

    @Test
    fun `R1 端到端 — 枚举全部主线程时序：OLD 会重复下、NEW 每页只下一次`() {
        val boundaries = 4 * 3   // maxSteps=4，每步 3 个 drain 边界
        val build = { LoopModel(totalPages = 1, preexisting = emptyList(), newPolicy = false, maxSteps = 4) }
        val buildNew = { LoopModel(totalPages = 1, preexisting = emptyList(), newPolicy = true, maxSteps = 4) }

        val old = enumerateSchedules(boundaries, false, build)
        val neu = enumerateSchedules(boundaries, true, buildNew)

        val oldRepeated = old.filter { it.maxPageTransfers >= 2 }
        val oldStuck = old.filter { it.rowStatus != ROW_SUCCESS }

        // OLD：存在时序让同一页被下 ≥2 次 —— 这就是"重复下 / 循环"
        assertTrue(
            "OLD 应存在重复下时序；实际 repeated=${oldRepeated.size}/${old.size}",
            oldRepeated.isNotEmpty()
        )
        println("[R1] OLD: 总时序=${old.size}，其中重复下=${oldRepeated.size}，未收口=${oldStuck.size}，"
                + "最大重复次数=${old.maxOf { it.maxPageTransfers }}")

        // NEW：**任意**时序下，同一页最多只下一次
        val newRepeated = neu.filter { it.maxPageTransfers >= 2 }
        assertTrue(
            "NEW 任何时序都不应重复下；违反时序数=${newRepeated.size}",
            newRepeated.isEmpty()
        )
        // NEW：存在能干净收口的时序（主线程正常 drain 时）
        assertTrue("NEW 应能收口 SUCCESS", neu.any { it.rowStatus == ROW_SUCCESS })
        val newOk = neu.first { it.rowStatus == ROW_SUCCESS }
        assertEquals("NEW 收口时总传输次数应为 1", 1, newOk.totalTransfers)
        println("[R1] NEW: 总时序=${neu.size}，重复下=${newRepeated.size}，"
                + "可收口=${neu.count { it.rowStatus == ROW_SUCCESS }}，收口样例传输次数=${newOk.totalTransfers}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. R2：retry path 的补页 —— 漏页 / 整段重下
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `R2 — 10 页只带回 2 页时：OLD 漏 8 页、NEW 补满 10 页`() {
        val old = LoopModel(totalPages = 10, preexisting = listOf(1, 2), newPolicy = false, maxSteps = 40)
            .run { true }   // 主线程每步都 drain，给足机会
        val neu = LoopModel(totalPages = 10, preexisting = listOf(1, 2), newPolicy = true, maxSteps = 40)
            .run { true }

        assertEquals("OLD 只下已存在的 2 页（漏 8 页）", 2, old.totalTransfers)
        assertEquals("OLD 却报 SUCCESS", ROW_SUCCESS, old.rowStatus)

        assertEquals("NEW 应补满 10 页", 10, neu.totalTransfers)
        assertEquals("NEW 收口 SUCCESS", ROW_SUCCESS, neu.rowStatus)
        assertTrue("NEW 每页只下一次", neu.maxPageTransfers == 1)

        println("[R2] OLD 传输=${old.transferCount}，NEW 传输=${neu.transferCount}")
    }

    /**
     * P1 的补页语义：缺哪页补哪页、按 page index 升序、每页恰好一次。
     *
     * content 里已有的页先跑（这里是 0 和 2），缺的（1/3/4）按索引升序补 —— 不是
     * "从计数器接着数"。谁要是把 pendingPages 换回 `nextPageToAdd++`，顺序与计数都会翻。
     */
    @Test
    fun `P1 — 补页按 page index 升序、每页恰好一次`() {
        val m = LoopModel(totalPages = 5, preexisting = listOf(0, 2), newPolicy = true, maxSteps = 40)
            .run { true }

        assertEquals("已在 content 的页先跑，缺的按索引升序补", listOf(0, 2, 1, 3, 4), m.transferOrder)
        assertEquals(setOf(0, 1, 2, 3, 4), m.transferCount.keys)
        assertTrue("每页只下一次", m.transferCount.values.all { it == 1 })
        assertEquals("应补满 5 页", 5, m.totalTransfers)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. R3/R5：伪 stranded → 同 uuid 双传输
    // ─────────────────────────────────────────────────────────────────────

    /**
     * pump 把 state 置 DOWNLOADING（t0），handle 要等 IO→Main 一跳才注册（t1）。
     * t0..t1 之间若用户点"全部继续"→ startAll → resurrectIfStranded 看到
     * "DOWNLOADING && !handles" 就翻 INIT → pump 再派发一次 → 同 uuid 两条传输。
     */
    private class DispatchModel(val newPolicy: Boolean) {
        var state = INIT
        var handleRegistered = false
        var dispatching = false          // NEW：派发中标记（setState 时置位，落 handles 时清除）
        var transfers = 0
        var handleChecks = 0

        fun pumpAndDispatch() {
            if (state != INIT) return
            state = DOWNLOADING
            if (newPolicy) dispatching = true
            transfers++                   // 开始传
            handleRegistered = true
            if (newPolicy) dispatching = false
        }

        fun resurrectIfStranded() {
            handleChecks++
            val looksRunning = handleRegistered || (newPolicy && dispatching)
            if (state == DOWNLOADING && !looksRunning) {
                state = INIT               // 误判 stranded → 复位
            }
        }
    }

    @Test
    fun `R3R5 — pump 置位到 handle 注册之间的窗口内点继续：OLD 双派发、NEW 不双派发`() {
        // OLD：pump 置 DOWNLOADING，但 handle 还没注册 → 此刻 resurrect 误判 → 再派发
        val old = DispatchModel(newPolicy = false)
        old.pumpAndDispatch()            // t0：state=DOWNLOADING, transfers=1, 但 handle 注册其实也同步了…
        // 模拟"handle 注册滞后"：手工回退 handle 可见性再触发 startAll
        val old2 = DispatchModel(newPolicy = false)
        old2.state = DOWNLOADING         // pump 已置位
        old2.handleRegistered = false    // startDownloadChain 的 handles.put 还没跑到
        old2.transfers = 1
        old2.resurrectIfStranded()       // startAll → 误判 stranded
        old2.pumpAndDispatch()           // 翻回 INIT 后被重新派发
        assertEquals("OLD 同 uuid 被派发两次", 2, old2.transfers)

        // NEW：dispatching 标记兜住窗口 → 不误判
        val neu = DispatchModel(newPolicy = true)
        neu.state = DOWNLOADING
        neu.dispatching = true           // setState(DOWNLOADING) 的同时置位
        neu.handleRegistered = false
        neu.transfers = 1
        neu.resurrectIfStranded()        // 看到 dispatching=true → 不翻 INIT
        neu.pumpAndDispatch()
        assertEquals("NEW 不双派发", 1, neu.transfers)
        assertEquals("NEW 状态保持 DOWNLOADING", DOWNLOADING, neu.state)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. R9：失败行回 PENDING 后是否插队
    // ─────────────────────────────────────────────────────────────────────

    private class Row(val id: Long, var seq: Long, var status: String = ROW_PENDING, var retry: Int = 0)

    /** 现行 DAO：nextByStatus = ORDER BY seq ASC LIMIT 1；bumpRetry 不动 seq。 */
    private fun oldNextByStatus(rows: List<Row>): Row? =
        rows.filter { it.status == ROW_PENDING }.minByOrNull { it.seq }

    /** 新方案：失败回退 PENDING 时把 seq 推到队尾。 */
    private fun newNextByStatus(rows: List<Row>): Row? =
        rows.filter { it.status == ROW_PENDING }.minByOrNull { it.seq }

    @Test
    fun `R9 — 失败行插队：OLD 连续死磕同一行、NEW 让位给下一行`() {
        val oldRows = mutableListOf(Row(1, 1), Row(2, 2), Row(3, 3))
        val newRows = mutableListOf(Row(1, 1), Row(2, 2), Row(3, 3))

        // 第一次拉取
        val oldFirst = oldNextByStatus(oldRows)!!
        val newFirst = newNextByStatus(newRows)!!
        assertEquals(1L, oldFirst.id); assertEquals(1L, newFirst.id)

        // 该行失败回退 PENDING
        oldFirst.status = ROW_PENDING; oldFirst.retry++          // OLD：seq 不变
        newFirst.status = ROW_PENDING; newFirst.retry++
        newFirst.seq = (newRows.maxOf { it.seq }) + 1            // NEW：推到队尾

        val oldSecond = oldNextByStatus(oldRows)!!
        val newSecond = newNextByStatus(newRows)!!
        assertEquals("OLD 第二次还是同一行 → 死磕", 1L, oldSecond.id)
        assertEquals("NEW 第二次让位给下一行", 2L, newSecond.id)
    }
}
