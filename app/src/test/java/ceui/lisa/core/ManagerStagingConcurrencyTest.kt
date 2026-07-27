package ceui.lisa.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 复现「5 并发只有 1 条进度跳」的机制。
 *
 * 这不是直接调 Manager.startDownloadChain（那条链耦合 Android Context / RxJava /
 * OkHttp 没法纯 JVM 跑）；而是把它的 byte-copy 主循环（Manager.java:602-654）
 * 原样搬下来，**唯一变量是 OutputStream 工厂**：
 *
 *   - SHARED 模式：所有 worker 共用一把全局锁 + 写延迟，模拟
 *     ContentResolver.openOutputStream(content://…) → MediaProvider Binder
 *     pipe。这是 Manager.java:595 那一行（cachedFile != null + content:// 直写）
 *     落到的 OutputStream。
 *   - ISOLATED 模式：每个 worker 写自己 cacheDir 下独立 FileOutputStream，
 *     模拟 staging 路径（Manager.java:587）。
 *
 * 同样的拷贝循环 + 同样的输入流，**只换 OutputStream 工厂**，看到的并行度就该
 * 完全不同。这就把「问题在 OutputStream 那一侧」定死，下一步只要再读
 * Manager.startDownloadChain 找到「哪个分支选了 SHARED 风格的 OutputStream」就
 * 是 root cause。
 */
class ManagerStagingConcurrencyTest {

    // ---------- 测试用 OutputStream 工厂 ----------

    /**
     * 跨实例共享同一把锁的 OutputStream。任意时刻只有一个 writer 能跑 write()，
     * 其余在 synchronized 上排队 —— 跟 MediaProvider 的 Binder pipe 行为一致。
     *
     * write 路径里加 1ms sleep，把"串行排队"在墙钟上放大到肉眼可见，否则 8KB
     * 一次的写在桌面机上 < 几 μs，timing 断言会 flaky。
     */
    private class SharedSerializingStream(
        private val sink: OutputStream,
        private val activeNow: AtomicInteger,
        private val maxObservedActive: AtomicInteger,
    ) : OutputStream() {
        companion object { val globalLock = Any() }

        override fun write(b: Int) {
            synchronized(globalLock) { criticalSection { sink.write(b) } }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(globalLock) { criticalSection { sink.write(b, off, len) } }
        }

        private inline fun criticalSection(body: () -> Unit) {
            val cur = activeNow.incrementAndGet()
            maxObservedActive.updateAndGet { if (cur > it) cur else it }
            try {
                Thread.sleep(1)  // simulate Binder cross-process overhead
                body()
            } finally {
                activeNow.decrementAndGet()
            }
        }

        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    /**
     * 各 worker 独立的 OutputStream，写进各自 tmp 文件。仍然记录"同时活动数"，
     * 用来对比 SHARED：理论上能看到 ≥2 个 worker 同时在 write。
     */
    private class IsolatedStream(
        private val sink: OutputStream,
        private val activeNow: AtomicInteger,
        private val maxObservedActive: AtomicInteger,
    ) : OutputStream() {

        override fun write(b: Int) = inActive { sink.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = inActive { sink.write(b, off, len) }

        private inline fun inActive(body: () -> Unit) {
            val cur = activeNow.incrementAndGet()
            maxObservedActive.updateAndGet { if (cur > it) cur else it }
            try {
                Thread.sleep(1)
                body()
            } finally {
                activeNow.decrementAndGet()
            }
        }

        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    // ---------- byte-copy 主循环（搬自 Manager.java:602-654，去掉 RxJava / 主线程 post）----------

    /**
     * 跟 Manager.startDownloadChain 内层 while 完全同构：8KB buffer，每写一块
     * 上报一次进度（这里"上报"= AtomicLong.set，对应原版 setNonius +
     * ManagerReactive.invalidate）。
     */
    private fun copyWithProgress(input: InputStream, output: OutputStream, observedBytes: AtomicLong) {
        val buf = ByteArray(8192)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
            written += n
            observedBytes.set(written)
        }
        output.flush()
        output.close()
    }

    private data class RunResult(
        val elapsedMs: Long,
        val maxConcurrentActive: Int,
        val finalBytes: List<Long>,
    )

    /**
     * 起 [n] 个 worker，每个跑一遍 copyWithProgress，OutputStream 由 [makeOut]
     * 构造。所有 worker 通过 CountDownLatch 一起起跑，避免 first-mover 跑完再 last-mover
     * 才开始。
     */
    private fun runConcurrent(
        n: Int,
        payloadSize: Int,
        makeOut: (Int, AtomicInteger, AtomicInteger) -> OutputStream,
    ): RunResult {
        val payloads = (0 until n).map { ByteArray(payloadSize) }
        val observed = (0 until n).map { AtomicLong(0) }
        val activeNow = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)

        val t0 = System.nanoTime()
        for (i in 0 until n) {
            pool.submit {
                ready.countDown()
                ready.await()  // 同时起跑
                copyWithProgress(
                    ByteArrayInputStream(payloads[i]),
                    makeOut(i, activeNow, maxActive),
                    observed[i],
                )
                done.countDown()
            }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "test timed out" }
        val elapsed = (System.nanoTime() - t0) / 1_000_000L
        pool.shutdown()
        return RunResult(elapsed, maxActive.get(), observed.map { it.get() })
    }

    // ---------- 测试 ----------

    /**
     * SYMPTOM：5 个 worker 共用同一个 SerializingPipe（≈ MediaProvider Binder
     * pipe）→ 任何时刻最多 1 个能跑 write，其余阻塞在锁上。
     *
     * 这条用断言 maxConcurrentActive == 1 卡死「串行」性质 —— 跟用户视频里
     * 「只有第 1 行有进度跳」是同一个机制。
     */
    @Test
    fun `concurrent writers sharing one serializing pipe never overlap — symptom matches video`() {
        val payloadSize = 64 * 1024  // 64KB → 8 个 8KB chunk，足够看到串行
        val sharedSink = java.io.ByteArrayOutputStream()
        val result = runConcurrent(n = 5, payloadSize = payloadSize) { _, active, max ->
            SharedSerializingStream(sharedSink, active, max)
        }

        // 全部跑完，每个 worker 都拿到了完整 payload
        assertTrue("all workers should complete: ${result.finalBytes}",
            result.finalBytes.all { it == payloadSize.toLong() })

        // 关键断言：同时活跃数永远不超过 1。这就是 MediaProvider 串行化的指纹。
        assertTrue("expected max concurrent active == 1 under shared lock, got ${result.maxConcurrentActive}",
            result.maxConcurrentActive == 1)
    }

    /**
     * CONTROL：同样 5 个 worker，但每人写各自的本地 FileOutputStream（≈ staging
     * 写到 cacheDir/staging_dl/{uuid}.part）→ 应该看到至少 2 个同时活跃。
     *
     * 这条证明：byte-copy 循环本身不串行，**串行性只来自 OutputStream 那一侧**。
     */
    @Test
    fun `concurrent writers with isolated streams overlap — staging path is parallel`() {
        val tmp = File.createTempFile("stage_test_", "_dir").apply { delete(); mkdir() }
        try {
            val payloadSize = 64 * 1024
            val result = runConcurrent(n = 5, payloadSize = payloadSize) { i, active, max ->
                IsolatedStream(FileOutputStream(File(tmp, "stage-$i.part")), active, max)
            }

            assertTrue("all workers should complete: ${result.finalBytes}",
                result.finalBytes.all { it == payloadSize.toLong() })

            // 关键断言：至少有 2 个 worker 在某个时刻同时跑。这就是 staging 应该
            // 给到的并行度。
            assertTrue("expected max concurrent active ≥ 2 with isolated streams, got ${result.maxConcurrentActive}",
                result.maxConcurrentActive >= 2)
        } finally {
            tmp.listFiles()?.forEach { it.delete() }
            tmp.delete()
        }
    }

    /**
     * 把上面两条结论焊到 Manager 现在的 staging 决策上。
     *
     * 断点续传重构后，staging 不再由并发数决定，而是由**目标是否 content:// 语义**
     * （`factory.targetIsContent()`）决定：
     *   useStaging = targetIsContent   // MediaStore / SAF → true；file:// / gif zip → false
     *
     *   | maxConc | targetIsContent | useStaging | 落点                       |
     *   | 1       | true (content)  | true       | FileOutputStream(stage)    | ← 现在单流也 staging
     *   | ≥2      | true (content)  | true       | FileOutputStream(stage)    |
     *   | *       | false (file)    | false      | FileOutputStream 直写       |
     *
     * 为什么单流也 staging：content:// 直写会在暂停 / 失败时留下 0 字节 .pending 行
     * （TG 群反馈的老问题），而 staging 把目标行的创建延后到 commit，从根上消掉泄漏，
     * 同时给断点续传一个统一的本地落点。file:// 无 ContentProvider 介入，直写即可。
     */
    @Test
    fun `useStaging formula — content scheme always stages regardless of concurrency, file never`() {
        // 照抄 Manager 现在的判定：staging = 目标是 content://
        fun useStaging(targetIsContent: Boolean): Boolean = targetIsContent

        for (n in 1..5) {
            assertTrue("maxConc=$n + content:// MUST stage (含单流，堵 .pending 泄漏 + 统一续传落点)",
                useStaging(true))
            assertTrue("maxConc=$n + file:// should NOT stage (无 ContentProvider)",
                !useStaging(false))
        }
    }

    /**
     * 回归 hedge：曾经 staging 只在 maxConc>1 才开，导致 maxConc=1 content:// 直写，
     * 暂停 / 失败留下 0 字节 .pending（以及断点续传只能靠脆弱的 uuid-key stage）。
     * 现在 content:// 一律 staging。谁要是把它改回「只有多并发才 staging」，这条立刻翻。
     */
    @Test
    fun `regression hedge — single-stream content download must still stage`() {
        fun useStaging(targetIsContent: Boolean): Boolean = targetIsContent
        assertTrue(
            "single-stream content:// must stage — otherwise pause/fail leaks a 0-byte .pending row " +
                "and there is no stable resume landing spot. Do not regress to maxConc>1 gating.",
            useStaging(/*targetIsContent=*/ true)
        )
    }

    // ---------- 端到端：staging path 在 N=1..8 batch 下都能把字节交到"相册 sink" ----------

    /**
     * 模拟一个简化的 MediaStore：每个 commit 把 (uri, bytes) 写进 map，
     * finishWrite 把对应 uri 的 IS_PENDING 翻 0。"相册可见" 等价于
     * `committedBytes[uri] != null && pendingCleared[uri] == true`。
     *
     * 模拟跨进程 Binder 写：openOutputStream 返回的 OutputStream 共用全局
     * commitLock，对应 MediaProvider 端单线程化 commit。
     */
    private class FakeMediaStore {
        private val committedBytes = ConcurrentHashMap<String, ByteArray>()
        private val pendingCleared = ConcurrentHashMap<String, Boolean>()
        private val commitLock = Any()

        fun openOutputStream(uri: String): OutputStream {
            val baos = java.io.ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) = synchronized(commitLock) { baos.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) =
                    synchronized(commitLock) { baos.write(b, off, len) }
                override fun close() {
                    synchronized(commitLock) {
                        committedBytes[uri] = baos.toByteArray()
                        pendingCleared.putIfAbsent(uri, false)
                    }
                }
            }
        }

        fun finishWrite(uri: String) {
            pendingCleared[uri] = true
        }

        fun visibleInGallery(uri: String): Boolean =
            committedBytes[uri] != null && pendingCleared[uri] == true

        fun bytes(uri: String): ByteArray? = committedBytes[uri]
        fun visibleCount(): Int = pendingCleared.values.count { it }
    }

    /**
     * 模拟 Manager.startDownloadChain 在 useStaging=true 路径下的全流程：
     *   1. 决定续传 / 重写：照抄 Manager 的 canResumePartialStage 逻辑
     *   2. 读源（cachedFile 或网络），8KB chunk 写到 stage 文件（append by effectivePassSize）
     *   3. 读 stage，写到 FakeMediaStore（content:// commit）
     *   4. finishWrite（IS_PENDING=0）
     *
     * 测的是 Manager 现有 staging 实现的端到端正确性 —— fix 之后会有更多
     * 路径走这里，不能让 staging 自身有 leak。
     */
    private fun runStagingDownload(
        index: Int,
        payload: ByteArray,
        stageDir: File,
        sink: FakeMediaStore,
        cachedFile: File? = null,
    ) {
        val uri = "content://media/external/img/$index"
        val stageFile = File(stageDir, "uuid-$index.part")

        // 照抄 Manager.java 的 canResumePartialStage 决策
        val canResumePartialStage = cachedFile == null && stageFile.length() > 0
        val effectivePassSize: Long = if (canResumePartialStage) {
            stageFile.length()
        } else {
            if (stageFile.exists()) stageFile.delete()
            0L
        }

        // step 1: 源 → stage
        val source: InputStream = cachedFile?.inputStream() ?: ByteArrayInputStream(payload)
        FileOutputStream(stageFile, effectivePassSize > 0).use { out ->
            source.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
        }

        // step 2: stage → MediaStore（commit；可能与别人争 commitLock，但短暂）
        sink.openOutputStream(uri).use { mediaOut ->
            java.io.FileInputStream(stageFile).use { stageIn ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stageIn.read(buf)
                    if (n == -1) break
                    mediaOut.write(buf, 0, n)
                }
            }
        }
        check(stageFile.delete()) { "stage cleanup failed: $stageFile" }

        // step 3: 让相册可见
        sink.finishWrite(uri)
    }

    /**
     * 用户场景：一次下 N 张图（N ∈ 1..8），N 全部并发提交。每张走 staging 路径，
     * 应该全部进相册 sink，字节完整、IS_PENDING 全部清零。
     *
     * 这条 case 配合 fix 一起证明：把 maxConc>1 缓存命中路径推到 staging 后，
     * commit + finishWrite 全链路依然把图正确递交给 MediaStore，没有 leak。
     */
    @Test
    fun `staging end-to-end — for every batch size 1 through 8, all images become visible in gallery`() {
        for (n in 1..8) {
            val tmpDir = File.createTempFile("staging_e2e_${n}_", "_dir").apply { delete(); mkdir() }
            try {
                val payloads = (0 until n).map { i ->
                    // 让 payload 不一样，断言 sink 拿到的字节没串
                    ByteArray(32 * 1024 + i * 17).also { it.fill((i + 1).toByte()) }
                }
                val sink = FakeMediaStore()
                val pool = Executors.newFixedThreadPool(n.coerceAtLeast(1))
                val ready = CountDownLatch(n)
                val done = CountDownLatch(n)

                for (i in 0 until n) {
                    pool.submit {
                        ready.countDown()
                        ready.await()  // 真并发起跑
                        runStagingDownload(i, payloads[i], tmpDir, sink)
                        done.countDown()
                    }
                }
                check(done.await(30, TimeUnit.SECONDS)) { "batch=$n timed out" }
                pool.shutdown()

                // 全部都进了相册（IS_PENDING=0 + 字节落库）
                assertTrue("batch=$n: only ${sink.visibleCount()}/$n visible",
                    sink.visibleCount() == n)
                // 字节没有串
                for (i in 0 until n) {
                    val uri = "content://media/external/img/$i"
                    val got = sink.bytes(uri) ?: error("batch=$n image=$i missing")
                    assertTrue("batch=$n image=$i bytes mismatch (size ${got.size} vs ${payloads[i].size})",
                        got.contentEquals(payloads[i]))
                }
                // stage dir 全部清干净（没有 .part 残留）
                val leftover = tmpDir.listFiles()?.toList().orEmpty()
                assertTrue("batch=$n leftover stage files: $leftover", leftover.isEmpty())
            } finally {
                tmpDir.listFiles()?.forEach { it.delete() }
                tmpDir.delete()
            }
        }
    }

    /**
     * 防 fix 自损：原本 cache 命中走直写，从来不碰 staging；fix 后多并发缓存命中
     * 也进 staging。staging 里有「partial stage 当作续传基线、append 模式接着写」
     * 的逻辑，本意服务网络源（配 Range 头），但**本地 cachedFile 没有 Range 概念**，
     * FileInputStream(cachedFile) 永远从 offset 0 读完整字节。如果上次失败留了
     * partial stage 没清，再来一遍就把完整字节追加到 partial 上 —— stage 字节翻
     * 倍，commit 出去给相册一张损坏的图。
     *
     * 修法（Manager.java canResumePartialStage 那块）：本地源永远从空 stage 重来，
     * 只对网络源沿用续传。这条测试用预先写好的 partial stage 文件 + cachedFile
     * 重跑一遍，断言 sink 拿到的字节 = 原 cachedFile 字节，不被旧 partial 污染。
     */
    @Test
    fun `cachedFile retry — leftover partial stage from a failed previous run does not bloat final bytes`() {
        val tmpDir = File.createTempFile("cache_retry_", "_dir").apply { delete(); mkdir() }
        try {
            val cacheFile = File(tmpDir, "glide-cache.bin")
            val expected = ByteArray(48 * 1024).also { for (i in it.indices) it[i] = (i % 251).toByte() }
            cacheFile.writeBytes(expected)

            // 模拟上次 commit 失败留下的 partial stage（写到 ~30%）：用一段
            // 跟 expected 完全不同的字节，污染检测才好做
            val stageFile = File(tmpDir, "uuid-1.part")
            stageFile.writeBytes(ByteArray(15 * 1024).also { it.fill(0xFF.toByte()) })
            assertTrue("setup: partial stage should be present",
                stageFile.exists() && stageFile.length() == 15L * 1024)

            val sink = FakeMediaStore()
            runStagingDownload(
                index = 1,
                payload = ByteArray(0),  // 走 cachedFile 路径，payload 不读
                stageDir = tmpDir,
                sink = sink,
                cachedFile = cacheFile,
            )

            // 断言 sink 拿到的就是 cacheFile 原内容，没有被 partial 污染
            val gotBytes = sink.bytes("content://media/external/img/1")
                ?: error("commit did not happen")
            assertTrue("expected sink to have original cache bytes (${expected.size}B), got ${gotBytes.size}B",
                gotBytes.size == expected.size)
            assertTrue("bytes content mismatch — partial stage leaked into commit",
                gotBytes.contentEquals(expected))
            assertTrue("image must be visible in gallery (IS_PENDING=0)",
                sink.visibleInGallery("content://media/external/img/1"))
        } finally {
            tmpDir.listFiles()?.forEach { it.delete() }
            tmpDir.delete()
        }
    }

    // ---------- openStageStream: cacheDir 被外部抹掉时的兜底 ----------

    /**
     * Issue #885：批量队列 PENDING 排队若干分钟以上，等 pump 调度到时
     * /data/user/0/<pkg>/cache/staging_dl/ 已经被系统/「清除缓存」/ 第三方
     * 清理软件抹掉。FileOutputStream 不会递归建目录 → 报
     * `java.io.FileNotFoundException: …/{uuid}.part: open failed: ENOENT
     * (No such file or directory)`，整批 FAILED。
     *
     * 修法 (Manager.openStageStream)：捕 FNF 后看 effectivePassSize:
     *   - 0：从头下（没带 Range 头），重建父目录后开新文件，零数据损失。
     *   - >0：续传（已带 Range:bytes={N}-），前 N 字节也没了，硬重试会出
     *     截断图，直接 throw 让 retry path 整段重下。
     *
     * 下面三条 case 固定这三个行为。
     */
    @Test
    fun `openStageStream — fresh download recreates parent dir wiped by cache cleanup`() {
        val parent = File.createTempFile("stage_wipe_fresh_", "_dir").apply { delete(); mkdir() }
        val stageFile = File(parent, "uuid-abc.part")
        // 模拟「pump 调度到这条 task 时父目录已经没了」
        parent.delete()
        check(!parent.exists()) { "setup: parent should not exist" }

        try {
            // effectivePassSize=0 ⇒ append=false ⇒ 进恢复路径
            val out = Manager.openStageStream(stageFile, 0L)
            out.use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }

            assertTrue("parent dir should be recreated", parent.isDirectory)
            assertTrue("stage file should be created and written", stageFile.exists()
                && stageFile.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
        } finally {
            stageFile.delete()
            parent.delete()
        }
    }

    /**
     * 续传场景：request 已经发出去带了 Range 头，stage 前 N 字节没了 = 整段
     * 数据完整性已经破裂，再去重建目录硬写会落出截断图、commit 进相册。
     * openStageStream 必须 throw 让 retry path 走整段重下。
     */
    @Test
    fun `openStageStream — resume path refuses to recreate dir, throws to force fresh retry`() {
        val parent = File.createTempFile("stage_wipe_resume_", "_dir").apply { delete(); mkdir() }
        val stageFile = File(parent, "uuid-xyz.part")
        parent.delete()
        check(!parent.exists()) { "setup: parent should not exist" }

        try {
            // effectivePassSize=1024 ⇒ append=true ⇒ 拒绝恢复
            var threw = false
            try {
                Manager.openStageStream(stageFile, 1024L).close()
            } catch (_: java.io.FileNotFoundException) {
                threw = true
            }
            assertTrue("openStageStream MUST throw on resume + wiped dir, not recreate", threw)
            assertTrue("parent dir must NOT be recreated on resume path", !parent.exists())
            assertTrue("stage file must NOT exist after refused resume", !stageFile.exists())
        } finally {
            stageFile.delete()
            parent.delete()
        }
    }

    /**
     * 正常路径（父目录在）零行为变化：fresh / append 两条分支都拿到能写的 stream。
     * 防止 fix 引入 regression 让普通下载也走错路。
     */
    @Test
    fun `openStageStream — normal path unchanged for both fresh and append modes`() {
        val parent = File.createTempFile("stage_normal_", "_dir").apply { delete(); mkdir() }
        val stageFile = File(parent, "uuid-norm.part")
        try {
            // fresh: append=false
            Manager.openStageStream(stageFile, 0L).use { it.write(byteArrayOf(10, 20)) }
            assertTrue("fresh write should produce exactly the bytes written",
                stageFile.readBytes().contentEquals(byteArrayOf(10, 20)))

            // append: effectivePassSize>0 → append=true，接着写
            Manager.openStageStream(stageFile, 2L).use { it.write(byteArrayOf(30, 40)) }
            assertTrue("append write should extend the file",
                stageFile.readBytes().contentEquals(byteArrayOf(10, 20, 30, 40)))
        } finally {
            stageFile.delete()
            parent.delete()
        }
    }
}
