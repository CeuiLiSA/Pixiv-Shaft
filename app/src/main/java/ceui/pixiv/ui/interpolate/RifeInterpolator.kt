package ceui.pixiv.ui.interpolate

import android.content.Context
import ceui.lisa.models.GifResponse
import timber.log.Timber
import java.io.File

/**
 * Ugoira RIFE 补帧核心 —— 把解压出来的帧目录喂给 librife_ncnn.so(nihui/rife-ncnn-vulkan
 * 的 Android 交叉编译版,和超分同一条 ProcessBuilder + Vulkan 链路),在每对相邻帧之间插入
 * 一张 AI 中间帧,帧数翻倍、每帧延迟减半。
 *
 * **一次 pass 直出 m 倍**,不做递归 2x。RIFE v4 是 timestep-conditioned 的,t=0.25 直接
 * 从原始相邻帧算,比「先合成中点、再拿合成帧当输入插一次」画质好(后者伪影会叠加),
 * 而且只启动一次进程(省一次 Vulkan 初始化)、不落中间帧(峰值磁盘减半)。
 *
 * 循环缝处理:ugoira 是无限循环的,尾帧→首帧之间也要有中间帧。做法是把首帧复制一份
 * 追加到输入序列末尾(M=N+1 帧),用 `-n m*(N+1)` 让 CLI 的均匀 timestep 正好落在
 * 1/m 的整数倍上,取前 m*N 帧(fi 从 0 到 N-1/m),尾帧回到首帧的间隔也正好是 1/m。
 *
 * CLI 的 timestep 公式经真机实测钉死为 `fi = i * M / T`(M=输入帧数, T=`-n` 值),
 * 且 fi 落在原帧上时是**逐字节透传**、超出范围时 clamp 重复末帧。验证方法:2 帧输入
 * 跑 `-n 4` 得 [A, mid, B, B],跑 `-n 8` 得 [A, ¼, ½, ¾, B, B, B, B],且两次的
 * ½ 帧 md5 相同 —— 若公式是 `i*(M-1)/(T-1)` 则 `-n 4` 应输出四帧互不相同。
 * 这是整套循环缝推导唯一的承重假设,改倍率/帧数前先重跑这个探针。
 *
 * 失败即放弃:任何一步不对(模型没下 / 进程非 0 / 输出帧数不符)都返回 null,
 * 调用方回落到原始帧序列,播放永远不会因为补帧挂掉。调用方取消([interpolate] 的
 * isActive 探针变 false)会立刻销毁子进程,同样返回 null。
 */
object RifeInterpolator {

    private const val TAG = "RifeInterpolate"
    private const val EXECUTABLE = "librife_ncnn.so"

    /**
     * 原始帧延迟低于该值(ms)就不补:减半后逼近 GIF 10ms 延迟粒度的下限,
     * 且 ≥25fps 的动图本来就不卡,补帧纯烧时间。
     */
    private const val MIN_DELAY_MS = 40

    /**
     * 补帧后单帧延迟的下限(ms)。GIF 延迟粒度 10ms,且 Glide 把 <20ms 的帧
     * 按 100ms 处理 —— 20ms(50fps)就是 GIF 容器的物理上限,"60帧"到此为止。
     */
    private const val TARGET_MIN_DELAY_MS = 20

    /** 最高补帧倍率(一次 pass 直出)。10fps 的典型动图 4x 后 40-50fps。 */
    private const val MAX_MULTIPLIER = 4

    /**
     * 补帧后总帧数上限。GIF 是个很差的容器:Pixel 8 实测 600x600 的补帧成品单帧
     * 56~187KB(画面越碎越大,4格漫画是最坏那档)、AnimatedGifEncoder 约 61ms/帧,
     * 而 `gif cache` 没有任何自动淘汰。300 帧 ≈ 18~56MB / 18s 编码,已是上限。
     *
     * 这个常量同时管两件事,所以是渐变而非一刀切:
     *  - [worthInterpolating]:源帧 N > MAX/2 完全不补
     *  - [multiplierFor]:4x 要 N ≤ MAX/4,2x 要 N ≤ MAX/2
     * 即 N≤75 吃满 4x、75~150 降 2x、150 以上不补。两处用的是同一个不等式,
     * 改这个值不会破坏「通过 worthInterpolating ⇒ 倍率至少 2x」的一致性。
     */
    private const val MAX_OUTPUT_FRAMES = 300

    /**
     * 单次 pass 的墙钟硬上限。进程 hang(Vulkan 初始化死锁类故障)时 isActive 探针
     * 只有「无人观察」才变 false,用户停在页面上就永远不触发 —— 这里兜底销毁,
     * 不让 GPU 串行闸门被一个僵死进程永久占用。实测 47帧×4x 只要 ~5s,远用不了这么久。
     */
    private const val PASS_TIMEOUT_MS = 10 * 60 * 1000L

    /** 模型在位 + 二进制在位,可以补。 */
    fun isAvailable(context: Context): Boolean {
        if (!RifeModelManager.isModelReady(context, RifeModel.RIFE_V4_6)) return false
        return File(context.applicationInfo.nativeLibraryDir, EXECUTABLE).exists()
    }

    /** 补帧产物:帧目录(2N 张,文件名字典序即播放序)+ 每帧延迟 ms。 */
    class Result(val framesDir: File, val delaysMs: List<Int>)

    /**
     * 判断这条 ugoira 值不值得补。[delays] 为每帧延迟(取自 ugoira_metadata,
     * 缺失时调用方已用 fallback 填齐)。
     */
    fun worthInterpolating(delays: List<Int>): Boolean {
        // 帧数上限和 multiplierFor 的 2x 门槛(n*2 ≤ MAX_OUTPUT_FRAMES)对齐;延迟下限
        // MIN_DELAY_MS 同理(40/2 = TARGET_MIN_DELAY_MS)。通过本检查 ⇒ multiplierFor
        // 至少给 2x,不存在「值得补但倍率算出 1」的矛盾分支。
        if (delays.size < 2 || delays.size * 2 > MAX_OUTPUT_FRAMES) return false
        return delays.min() >= MIN_DELAY_MS
    }

    /**
     * 这条 ugoira 该补几倍:每轮 2x,直到「补后延迟会低于 [TARGET_MIN_DELAY_MS]」
     * 或「总帧数超 [MAX_OUTPUT_FRAMES]」或到 [MAX_MULTIPLIER]。返回 1 表示不值得补。
     */
    private fun multiplierFor(frameCount: Int, delays: List<Int>): Int {
        val minDelay = delays.min()
        var k = 1
        while (k < MAX_MULTIPLIER &&
            minDelay / (2 * k) >= TARGET_MIN_DELAY_MS &&
            frameCount * 2 * k <= MAX_OUTPUT_FRAMES
        ) {
            k *= 2
        }
        return k
    }

    /**
     * 同步阻塞跑完整补帧,须在 IO 线程调用。[unzipFolder] 是原始帧目录,
     * [delays] 与帧一一对应。[onProgress] 0..100(按输出帧文件数轮询)。
     *
     * [isActive] 每 300ms 轮询一次:协程取消不会 interrupt 阻塞线程,调用方把存活探针
     * 传进来,变 false 时立刻销毁 rife 子进程并返回 null —— 不烧无人看的 GPU。
     * [workRoot] 整棵树(含返回的 [Result.framesDir])由调用方负责用完后删除;
     * 本方法只保证失败/取消路径不留中间产物。
     *
     * 倍率自适应(2x/4x):典型 10-15fps 的动图补到 40-50fps,贴着 GIF 的 50fps 上限走。
     */
    fun interpolate(
        context: Context,
        unzipFolder: File,
        delays: List<Int>,
        workRoot: File,
        isActive: () -> Boolean = { true },
        onProgress: (Int) -> Unit = {},
    ): Result? {
        val frames = (unzipFolder.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
        val n = frames.size
        if (n < 2 || n != delays.size) {
            Timber.tag(TAG).w("帧数不对 frames=%d delays=%d,放弃", n, delays.size)
            return null
        }
        val multiplier = multiplierFor(n, delays)
        if (multiplier < 2) return null

        val t0 = System.currentTimeMillis()
        val outDir = File(workRoot, "rife_out")
        try {
            if (!runPass(context, frames, outDir, workRoot, multiplier, isActive, onProgress)) {
                outDir.deleteRecursively()
                return null
            }

            // 延迟:原帧 d 均分成 multiplier 段,逐段累积取整到 10ms 粒度 ——
            // 每段都是整数 cs(GIF 真实粒度)、总时长精确等于 d,量化误差不随帧累积。
            val newDelays = ArrayList<Int>(n * multiplier)
            for (d in delays) {
                var assigned = 0
                for (j in 1..multiplier) {
                    val target = (j * d.toDouble() / multiplier / 10.0).let { Math.round(it) * 10 }.toInt()
                    newDelays.add(target - assigned)
                    assigned = target
                }
            }
            onProgress(100)
            Timber.tag(TAG).i(
                "补帧完成 %d帧 ×%d → %d帧 耗时 %dms",
                n, multiplier, n * multiplier, System.currentTimeMillis() - t0,
            )
            return Result(outDir, newDelays)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "补帧异常,放弃")
            outDir.deleteRecursively()
            return null
        }
    }

    /**
     * 一次 pass 出 [multiplier]×N 帧(尾→首循环缝也有中间帧)。
     * 做法:输入目录 = 顺序重命名副本 + 追加首帧(M=N+1 帧),`-n multiplier*(N+1)` 让 CLI 的
     * 均匀 timestep 落在 1/[multiplier] 的整数倍上;输出取前 multiplier*N 帧,
     * 丢掉尾部 multiplier 帧(首帧副本 + 其 clamp 重复)。
     */
    private fun runPass(
        context: Context,
        frames: List<File>,
        outDir: File,
        workRoot: File,
        multiplier: Int,
        isActive: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val n = frames.size
        val modelDir = RifeModelManager.modelDir(context, RifeModel.RIFE_V4_6)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val executable = File(nativeDir, EXECUTABLE)

        val inDir = File(workRoot, "rife_in")
        inDir.deleteRecursively(); outDir.deleteRecursively()
        inDir.mkdirs(); outDir.mkdirs()
        try {
            val ext = frames.first().extension.ifEmpty { "png" }
            frames.forEachIndexed { i, f ->
                f.copyTo(File(inDir, "%08d.%s".format(i + 1, ext)))
            }
            frames.first().copyTo(File(inDir, "%08d.%s".format(n + 1, ext)))

            val targetCount = multiplier * (n + 1)
            val args = listOf(
                executable.absolutePath,
                "-i", inDir.absolutePath,
                "-o", outDir.absolutePath,
                "-m", modelDir.absolutePath,
                "-n", targetCount.toString(),
                "-g", "0",
            )
            Timber.tag(TAG).i("启动补帧 %d帧 → %d帧: %s", n, targetCount, args.joinToString(" "))
            val process = ProcessBuilder(args)
                .apply { environment()["LD_LIBRARY_PATH"] = nativeDir }
                .redirectErrorStream(true)
                .start()

            // CLI 没有帧级进度输出,轮询输出目录文件数近似;同时把 stderr 喂给 log 防塞管道。
            // destroy() 会关流抛 IOException,而守护线程的未捕获异常会带崩整个 app —— 必须兜住。
            val logThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) Timber.tag(TAG).d("[rife] %s", line)
                    }
                }
            }.apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + PASS_TIMEOUT_MS
            while (process.isAliveCompat()) {
                if (!isActive()) {
                    Timber.tag(TAG).i("调用方已取消,销毁 rife 进程")
                    process.destroy()
                    process.waitForCompat(2_000)
                    return false
                }
                if (System.currentTimeMillis() > deadline) {
                    Timber.tag(TAG).w("单轮补帧超过 %dms,疑似进程僵死,销毁", PASS_TIMEOUT_MS)
                    process.destroy()
                    process.waitForCompat(2_000)
                    return false
                }
                val done = outDir.listFiles()?.size ?: 0
                onProgress((done * 100 / targetCount).coerceAtMost(99))
                if (!process.waitForCompat(300)) continue
            }
            logThread.join(2000)
            if (process.exitValue() != 0) {
                Timber.tag(TAG).w("rife 进程退出码 %d,放弃", process.exitValue())
                return false
            }

            val outputs = (outDir.listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            if (outputs.size < targetCount) {
                Timber.tag(TAG).w("输出帧数 %d < 期望 %d,放弃", outputs.size, targetCount)
                return false
            }
            // 只留前 multiplier*N 帧:尾部 multiplier 帧是首帧副本(fi=N)及其 clamp 重复
            outputs.takeLast(outputs.size - multiplier * n).forEach { runCatching { it.delete() } }
            return true
        } finally {
            inDir.deleteRecursively()
        }
    }
}

// Process.isAlive / waitFor(timeout) 都要 API 26（minSdk 24，lintVital 拦 release
// 构建）。exitValue 在进程未退出时抛 ISE，是 API 1 的等价判活；带超时的等待用
// 判活 + sleep 轮询近似——这里本来就是 300ms 粒度的轮询循环，不需要精确唤醒。

private fun Process.isAliveCompat(): Boolean =
    try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

/** 等进程退出，最多 [timeoutMs] 毫秒；返回 true = 已退出。 */
private fun Process.waitForCompat(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (isAliveCompat()) {
        if (System.currentTimeMillis() >= deadline) return false
        Thread.sleep(50)
    }
    return true
}
