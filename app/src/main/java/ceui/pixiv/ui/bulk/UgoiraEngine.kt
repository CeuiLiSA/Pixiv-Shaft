package ceui.pixiv.ui.bulk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ceui.lisa.activities.Shaft
import ceui.lisa.cache.Cache
import ceui.lisa.file.LegacyFile
import ceui.lisa.http.ImageHostManager
import ceui.lisa.http.Retro
import ceui.lisa.models.FramesBean
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.AnimatedGifEncoder
import ceui.lisa.utils.Params
import ceui.pixiv.ui.interpolate.RifeInterpolator
import com.blankj.utilcode.util.ZipUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** ugoira 加载/播放全链路统一日志 tag —— `adb logcat -s UgoiraFlow` 就能看完整流程卡在哪。 */
const val UGOIRA_LOG_TAG = "UgoiraFlow"

/**
 * 播放引擎进度回调载荷。[percent] 为 null 表示该阶段没有字节/帧级 % 可报(转圈),
 * 非 null 是 0..100。慢阶段(zip 下载 / GIF 编码)会持续回 percent。
 */
data class UgoiraProgress(val phase: UgoiraPhase, val percent: Int? = null)

/**
 * Ugoira 播放引擎 —— 完全不耦合 UI 的「把一条 ugoira 变成可直接喂给 ImageLoader 的
 * GIF 文件」全链路。旧的 [ceui.lisa.fragments.FragmentSingleUgora] 把这套逻辑和
 * 具体 View / LocalBroadcast(PLAY_GIF) / 全局 Manager 回调 / MMKV flag 缠在一起;
 * 这里抽成一个不认识任何 View 的引擎。
 *
 * **工作不挂 Fragment 生命周期**:每条 ugoira 的 meta→下载→解压→编码跑在引擎自己的
 * [engineScope],结果放进 [jobs] 里的共享 [Deferred]。退出详情页只会取消「等待 + 观察进度」,
 * 底层任务继续把 gif 编完落缓存;再进来 [loadPlayableGif] 直接命中缓存或 join 同一个还在跑的
 * 任务并接着显示进度——修「下 zip / 编码途中退出再进,一直卡加载」。
 *
 * **并发/回收**:详情页 ViewPager 会同时 resume 3 个页 + 来回滑动,不设限会有五六条 zip 同时下
 * 互抢带宽。所以重活走 [gate] 限并发([MAX_CONCURRENT]);并用 [refs] 数观察者,划走 [ABANDON_GRACE_MS]
 * 还没人看就取消后台任务(省流量 + 让出额度)。来回滑动在宽限期内不会误杀。
 *
 * 进度用 [progressOf] 的 [StateFlow] 广播,跨 Fragment 共享:再进来 collect 立刻拿到当前阶段。
 *
 * 下载/编码/OkHttp client 与保存链路 [downloadUgoira] 共用同一套 [downloadZipTo] /
 * [encodeFramesToGif] / [ugoiraHttpClient]。
 */
object UgoiraEngine {

    private const val MIN_VALID_GIF_BYTES = 1024L

    // 同时最多几条 ugoira 在跑「下载+编码」重活。实测不设限时 1MB 的 zip 因互抢带宽下了 39s。
    private const val MAX_CONCURRENT = 2

    // 划走后多久没人看就取消后台任务。来回滑动在宽限期内不会误杀。
    private const val ABANDON_GRACE_MS = 12000L

    // 引擎级 scope:SupervisorJob 让单条失败不拖垮别条;不随任何 Fragment 取消。
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 重活(下载+解压+编码)的并发闸门。meta / 缓存命中不占额度。
    private val gate = Semaphore(MAX_CONCURRENT)

    /** illustId -> 已生成好的可播放 gif(内存快路径)。 */
    private val readyGifCache = ConcurrentHashMap<Int, File>()

    /** illustId -> 进度广播,跨 Fragment 共享。 */
    private val progressFlows = ConcurrentHashMap<Int, MutableStateFlow<UgoiraProgress>>()

    // 下面三张表统一用 [lock] 保护:「查任务 + 改观察者计数 + 撤销/安排取消」要整体原子。
    private val lock = Any()
    private val jobs = HashMap<Int, Deferred<File>>()   // illustId -> 正在跑的共享任务
    private val refs = HashMap<Int, Int>()              // illustId -> 当前观察者数
    private val cancelTimers = HashMap<Int, Job>()      // illustId -> 待触发的「划走取消」计时器

    /** 观察某条 ugoira 的加载进度(进来立刻拿当前值)。 */
    fun progressOf(illustId: Int): StateFlow<UgoiraProgress> = flowFor(illustId).asStateFlow()

    private fun flowFor(illustId: Int): MutableStateFlow<UgoiraProgress> =
        progressFlows.computeIfAbsent(illustId) {
            MutableStateFlow(UgoiraProgress(UgoiraPhase.FETCH_META))
        }

    /**
     * 同步 peek 已编好的可播放 gif(内存 or 磁盘 [LegacyFile.gifResultFile]),没有返回 null。
     * 保存链路 [downloadUgoira] 用它复用播放引擎已产出的 gif —— 用户先看过就直接拷,
     * 不再重下 zip / 重解压 / 重编上百帧。只读文件系统,须在 IO 线程调用。
     */
    fun peekPlayableGif(illust: IllustsBean): File? {
        val id = illust.id
        readyGifCache[id]?.let { if (it.isValidGif()) return it }
        val f = LegacyFile.gifResultFile(Shaft.getContext(), illust)
        if (f.isValidGif()) {
            readyGifCache[id] = f
            return f
        }
        return null
    }

    /** 纯内存 peek —— 已编好的 gif 直接给,不碰文件系统。播放器主线程「秒开」专用(零 IO,免 ANR)。 */
    fun peekReadyInMemory(illustId: Int): File? = readyGifCache[illustId]

    /** gif 加载失败(疑似系统清了缓存目录)→ 清掉内存记录,下次 [loadPlayableGif] 走完整 pipeline 重新落盘。 */
    fun invalidate(illustId: Int) {
        readyGifCache.remove(illustId)
    }

    /** RIFE 补帧开关切换时调用:内存里记的是旧变体(原速/补帧)的 gif,全清,下次按新开关重取。 */
    @JvmStatic
    fun invalidateAll() {
        readyGifCache.clear()
    }

    /**
     * 本次播放该用哪个 gif 变体。开关开 + 模型在位 → 补帧变体(独立缓存文件,
     * 和原速 gif 互不覆盖,关掉开关旧缓存还能直接用);否则原文件。
     */
    private fun resultFileFor(ctx: android.content.Context, illust: IllustsBean, useRife: Boolean): File {
        val base = LegacyFile.gifResultFile(ctx, illust)
        return if (useRife) File(base.parentFile, base.nameWithoutExtension + "_rife.gif") else base
    }

    /**
     * 拿可播放 GIF 文件。命中缓存直接返回;否则复用/新建一个引擎级共享任务并 await。
     * **await 被取消(Fragment 退出)不取消底层任务**,只把观察者计数减一;划走够久没人看才回收。
     */
    suspend fun loadPlayableGif(illust: IllustsBean): File {
        val id = illust.id
        // 内存命中直接给,不做 isValidGif 的文件 stat —— loadPlayableGif 在播放器主线程调用,
        // 不能碰文件系统(免 disk-on-main / ANR)。文件真被系统清了缓存,Glide 加载会失败,
        // 播放器 onLoadFailed 里 invalidate 后再走完整 pipeline 重来。
        readyGifCache[id]?.let {
            Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableGif] illust=%d 内存缓存命中,直接播放 -> %s", id, it.name)
            return it
        }
        val deferred = acquireJob(illust)
        try {
            return deferred.await()
        } finally {
            releaseJob(id)
        }
    }

    /** 观察者 +1,拿到(或新建)共享任务;撤销任何待触发的「划走取消」。 */
    private fun acquireJob(illust: IllustsBean): Deferred<File> = synchronized(lock) {
        val id = illust.id
        val count = (refs[id] ?: 0) + 1
        refs[id] = count
        cancelTimers.remove(id)?.cancel() // 有人(重新)进来了,别取消
        jobs[id]?.let { existing ->
            Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableGif] illust=%d join 正在跑的任务,await(观察者=%d)", id, count)
            return@synchronized existing
        }
        Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableGif] illust=%d 启动新任务,await", id)
        val d = engineScope.async { runPipeline(illust) }
        jobs[id] = d
        d
    }

    /** 观察者 -1;归零且任务还在跑 → 宽限期后仍没人看就取消(省流量 + 让出并发额度)。 */
    private fun releaseJob(id: Int): Unit = synchronized(lock) {
        val n = (refs[id] ?: 1) - 1
        if (n > 0) {
            refs[id] = n
            return@synchronized
        }
        refs.remove(id)
        val d = jobs[id] ?: return@synchronized
        if (!d.isActive) return@synchronized // 已经编完了,不用管
        cancelTimers[id] = engineScope.launch {
            delay(ABANDON_GRACE_MS)
            // delay 走完后这段 synchronized 尾巴不可再被 cancel() 打断;所以只认「我还是登记在案
            // 的那个计时器」才动手 —— 期间若有人重进又离开、换上了新计时器/新任务,别误删新的。
            val self = coroutineContext[Job]
            synchronized(lock) {
                if (cancelTimers[id] === self && (refs[id] ?: 0) == 0) {
                    cancelTimers.remove(id)
                    jobs.remove(id)?.let {
                        it.cancel()
                        Timber.tag(UGOIRA_LOG_TAG).i("[engine] illust=%d 划走 %dms 无人看,取消后台任务", id, ABANDON_GRACE_MS)
                    }
                }
            }
        }
    }

    private suspend fun runPipeline(illust: IllustsBean): File {
        val id = illust.id
        val flow = flowFor(id)
        val t0 = System.currentTimeMillis()
        Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d ===== START =====", id)
        try {
            val ctx = Shaft.getContext()

            // 磁盘上已有编好的最终 gif:直接用。补帧开关决定用哪个变体文件。
            val useRife = Shaft.sSettings.isUgoiraRifeEnable() && RifeInterpolator.isAvailable(ctx)
            val resultFile = resultFileFor(ctx, illust, useRife)
            if (resultFile.isValidGif()) {
                readyGifCache[id] = resultFile
                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 磁盘缓存命中 -> %s (%d bytes),直接返回", id, resultFile.name, resultFile.length())
                return resultFile
            }

            // 1/4 元数据(轻,不占并发额度)
            flow.value = UgoiraProgress(UgoiraPhase.FETCH_META)
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [1/4] FETCH_META 开始", id)
            val resp = fetchMeta(id)
            val zipUrl = resp.ugoira_metadata?.zip_urls?.medium
                ?: throw IllegalStateException("ugoira zip url missing for illust=$id")
            val frameCount = resp.ugoira_metadata?.frames?.size ?: 0
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [1/4] FETCH_META 完成 frames=%d zipUrl=%s", id, frameCount, zipUrl)
            coroutineContext.ensureActive()

            // 下载 / 解压 / 编码是重活,占并发额度:超过 MAX_CONCURRENT 的在 withPermit 排队等,
            // 期间 last-phase 仍是 FETCH_META(诚实——它真的还没在下)。等待时若被划走取消,
            // withPermit 的 acquire 可取消,直接不下,连额度都不占。
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 申请并发额度(空闲=%d)…", id, gate.availablePermits)
            gate.withPermit {
                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 拿到并发额度,开始下载/编码", id)
                coroutineContext.ensureActive()

                // 2/4 下载 zip
                val zipFile = LegacyFile.gifZipFile(ctx, illust)
                if (!zipFile.isFile || zipFile.length() == 0L) {
                    flow.value = UgoiraProgress(UgoiraPhase.DOWNLOAD_ZIP)
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [2/4] DOWNLOAD_ZIP 开始 -> %s", id, zipFile.name)
                    var lastQuarter = -1
                    downloadZipTo(zipUrl, zipFile) { pct ->
                        flow.value = UgoiraProgress(UgoiraPhase.DOWNLOAD_ZIP, pct)
                        if (pct / 25 != lastQuarter) {
                            lastQuarter = pct / 25
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [2/4] DOWNLOAD_ZIP %d%%", id, pct)
                        }
                    }
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [2/4] DOWNLOAD_ZIP 完成 (%d bytes)", id, zipFile.length())
                } else {
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [2/4] DOWNLOAD_ZIP 跳过(zip 已缓存 %d bytes)", id, zipFile.length())
                }
                coroutineContext.ensureActive()

                // 3/4 解压
                val unzipFolder = LegacyFile.gifUnzipFolder(ctx, illust)
                val expected = resp.ugoira_metadata?.frames?.size ?: 0
                val onDisk = unzipFolder.listFiles()?.count { it.isFile } ?: 0
                if (onDisk == 0 || (expected > 0 && onDisk != expected)) {
                    if (onDisk > 0) unzipFolder.listFiles()?.forEach { runCatching { it.delete() } }
                    flow.value = UgoiraProgress(UgoiraPhase.EXTRACT)
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 开始 (磁盘有 %d 帧,期望 %d)", id, onDisk, expected)
                    ZipUtils.unzipFile(zipFile, unzipFolder)
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 完成 (%d 帧)", id, unzipFolder.listFiles()?.size ?: 0)
                } else {
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 跳过(已解压 %d 帧)", id, onDisk)
                }
                coroutineContext.ensureActive()

                // 3.5/4 RIFE 补帧(可选):在编码前把帧序列翻倍、延迟减半。任何失败都回落
                // 原始帧,播放不因补帧挂掉。补帧工作目录整棵树(中间产物 + 输出帧)由最外层
                // finally 兜底删除 —— 成功/失败/任意取消点都不在磁盘残留,只留最终 gif。
                var encodeFrames: List<File>? = null
                var encodeDelays: List<Int>? = null
                val rifeWorkRoot = File(ctx.cacheDir, "rife_work_$id")
                try {
                    if (useRife) {
                        val srcFrames = sortedUgoiraFrames(unzipFolder)
                        val srcDelays = ugoiraDelays(srcFrames.size, resp)
                        if (RifeInterpolator.worthInterpolating(srcDelays)) {
                            flow.value = UgoiraProgress(UgoiraPhase.INTERPOLATE, 0)
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 开始 (%d帧)", id, srcFrames.size)
                            var lastQuarter = -1
                            // 协程取消不会 interrupt 阻塞线程 —— 把存活探针传给插值器,划走
                            // 取消时它自行销毁 rife 子进程,立刻让出并发额度、停烧 GPU。
                            val pipelineJob = coroutineContext[Job]
                            val rife = RifeInterpolator.interpolate(
                                ctx, unzipFolder, srcDelays,
                                workRoot = rifeWorkRoot,
                                isActive = { pipelineJob?.isActive != false },
                            ) { pct ->
                                flow.value = UgoiraProgress(UgoiraPhase.INTERPOLATE, pct)
                                if (pct / 25 != lastQuarter) {
                                    lastQuarter = pct / 25
                                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE %d%%", id, pct)
                                }
                            }
                            if (rife != null) {
                                encodeFrames = sortedUgoiraFrames(rife.framesDir)
                                encodeDelays = rife.delaysMs
                                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 完成 → %d帧", id, encodeFrames.size)
                            } else {
                                Timber.tag(UGOIRA_LOG_TAG).w("[pipeline] illust=%d [3.5/4] INTERPOLATE 未产出(取消/失败),回落原始帧", id)
                            }
                        } else {
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 跳过(帧率已高或帧数超限)", id)
                        }
                    }
                    coroutineContext.ensureActive()

                    // 4/4 编码。先写 .part 再 rename —— Glide 永远不会读到半张 gif。
                    flow.value = UgoiraProgress(UgoiraPhase.ENCODE, 0)
                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [4/4] ENCODE 开始", id)
                    resultFile.parentFile?.mkdirs()
                    val temp = File(resultFile.parentFile, resultFile.name + ".part")
                    try {
                        var lastQuarter = -1
                        val onEncodePct: (Int) -> Unit = { pct ->
                            flow.value = UgoiraProgress(UgoiraPhase.ENCODE, pct)
                            if (pct / 25 != lastQuarter) {
                                lastQuarter = pct / 25
                                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [4/4] ENCODE %d%%", id, pct)
                            }
                        }
                        BufferedOutputStream(FileOutputStream(temp)).use { bos ->
                            if (encodeFrames != null && encodeDelays != null) {
                                encodeFramesToGif(encodeFrames, encodeDelays, bos, onEncodePct)
                            } else {
                                encodeFramesToGif(unzipFolder, resp, bos, onEncodePct)
                            }
                        }
                        if (resultFile.exists()) resultFile.delete()
                        if (!temp.renameTo(resultFile)) {
                            throw IllegalStateException("rename .part → ${resultFile.name} failed")
                        }
                    } catch (t: Throwable) {
                        runCatching { temp.delete() }
                        throw t
                    }
                } finally {
                    rifeWorkRoot.deleteRecursively()
                }
            }
            readyGifCache[id] = resultFile
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d ===== SUCCESS ===== %s (%d bytes) 耗时 %dms", id, resultFile.name, resultFile.length(), System.currentTimeMillis() - t0)
            return resultFile
        } catch (c: CancellationException) {
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 已取消(划走无人看 / 进程回收) 耗时 %dms", id, System.currentTimeMillis() - t0)
            throw c
        } catch (t: Throwable) {
            Timber.tag(UGOIRA_LOG_TAG).e(t, "[pipeline] illust=%d ===== FAILED ===== 耗时 %dms", id, System.currentTimeMillis() - t0)
            throw t
        } finally {
            // 只在 jobs[id] 还是「本协程」时才清理 —— 否则会误删并发 acquireJob 刚装进去的新任务
            // (abandon 计时器已取消我并 remove 后,新观察者重开的 d2),让 d2 变成 map 外孤儿,
            // 下一个 acquireJob 又建 d3,两条 pipeline 同写 gifZipFile.part → 可能损坏。
            val myJob = coroutineContext[Job]
            synchronized(lock) {
                if (jobs[id] === myJob) {
                    jobs.remove(id)
                    cancelTimers.remove(id)?.cancel()
                }
            }
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d END", id)
        }
    }

    private fun File.isValidGif() = isFile && length() > MIN_VALID_GIF_BYTES

    /** 元数据优先取 [Cache] 里已有的 [GifResponse],否则 getGifPackage 拉一次并回写缓存。 */
    private suspend fun fetchMeta(illustId: Int): GifResponse {
        val cached = runCatching {
            Cache.get().getModel(Params.ILLUST_ID + "_" + illustId, GifResponse::class.java)
        }.getOrNull()
        if (cached?.ugoira_metadata != null) {
            Timber.tag(UGOIRA_LOG_TAG).i("[fetchMeta] illust=%d 命中本地 Cache", illustId)
            return cached
        }
        Timber.tag(UGOIRA_LOG_TAG).i("[fetchMeta] illust=%d 走网络 getGifPackage…", illustId)
        val fetched = Retro.getAppApi().getGifPackage(illustId).awaitFirstSafe()
        runCatching { Cache.get().saveModel(Params.ILLUST_ID + "_" + illustId, fetched) }
        Timber.tag(UGOIRA_LOG_TAG).i("[fetchMeta] illust=%d 网络返回", illustId)
        return fetched
    }
}

// ── 下面三个是保存链路([downloadUgoira])与播放链路([UgoiraEngine])共用的纯工具, ──
// ── 无状态,提到 top-level(internal)让两条链路各调各的,别再各留一份拷贝。       ──

private const val UGOIRA_PIPELINE_TAG = "UgoiraPipeline"

// 复用 Glide 图片客户端:PIXIV 模式带直连加速(HttpDns IP 直连 + 无 SNI TLS,绕 GFW),
// 代理模式(pixiv.cat/re/nl/自定义)是标准 DNS+TLS —— 和 app 加载图片同一条快路,只把
// 读超时放宽到 120s 给大 zip。之前用裸 client 直连 i.pximg.net,墙内慢到 65KB/s(1MB 下 39s)。
internal val ugoiraHttpClient: OkHttpClient by lazy {
    (Shaft.getContext() as Shaft).okHttpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

/**
 * OkHttp 直下 zip 到 [target]。pixiv 服务器要 Referer，否则 403。
 * 写到 .part 临时文件，完成后 rename —— 中途中断不会留 0 字节文件让下次跳过。
 * [onProgress] 只在整数 % 变化时回调（服务器给了 Content-Length 才有 %）。
 */
internal suspend fun downloadZipTo(url: String, target: File, onProgress: (Int) -> Unit = {}) {
    // 和 GlideUrlChild / Manager 同款:按用户选的图片 host 重写(i.pximg.net → 代理),
    // path-agnostic 所以 zip 路径照样走代理;PIXIV 模式是 no-op(配合上面直连 client 加速)。
    val realUrl = ImageHostManager.rewrite(url)
    Timber.tag(UGOIRA_LOG_TAG).i("[downloadZipTo] 实际下载 URL=%s", realUrl)
    val req = Request.Builder()
        .url(realUrl)
        .header("Referer", Params.IMAGE_REFERER)
        .header("User-Agent", Params.PHONE_MODEL)
        .build()
    ugoiraHttpClient.newCall(req).execute().use { r ->
        if (!r.isSuccessful) {
            throw IllegalStateException("zip download HTTP ${r.code} url=$url")
        }
        val body = r.body ?: throw IllegalStateException("zip body null url=$url")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        val contentLength = body.contentLength() // -1 / 0 = 服务器没给,保持转圈不报 %
        // Response.use 已经会关 body 流，body.byteStream() 不必再嵌套 use
        FileOutputStream(temp).use { out ->
            val input = body.byteStream()
            val buf = ByteArray(16 * 1024)
            var readTotal = 0L
            var lastPct = -1
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (contentLength > 0) {
                    readTotal += n
                    // 只在整数 % 变化时回调,避免几十 MB zip 刷爆回调/主线程。
                    val pct = (readTotal * 100 / contentLength).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            throw IllegalStateException("rename .part → ${target.name} failed")
        }
    }
}

/**
 * 把 [unzipFolder] 里 `001.png / 002.png ...` 帧文件按顺序编进 GIF，写到 [out]。
 * 帧延迟优先取 [GifResponse.ugoira_metadata].frames（每帧独立），fallback 到
 * [GifResponse.getDelay]（单值），再 fallback 到默认 60ms。
 *
 * 直接 BitmapFactory.decodeFile + recycle —— 同步阻塞，谁调用谁负责进 IO 线程。
 * 100 帧的常见 ugoira 在 Pixel 上 ~1s 完成。[onProgress] 每帧回一次 0..100。
 *
 * **不持有/不关闭 [out]** —— 调用方负责（[BufferedOutputStream.use] / V3 WriteHandle
 * 的 onFinish 收尾）。这里调 [AnimatedGifEncoder.finish] 写出 GIF trailer 即可，
 * 不要再 close。
 */
internal fun encodeFramesToGif(
    unzipFolder: File,
    resp: GifResponse,
    out: OutputStream,
    onProgress: (Int) -> Unit = {},
) {
    val files = sortedUgoiraFrames(unzipFolder)
    if (files.isEmpty()) throw IllegalStateException("no frames to encode in $unzipFolder")
    encodeFramesToGif(files, ugoiraDelays(files.size, resp), out, onProgress)
}

/** 帧文件按数字文件名排序(文件名形如 "000123.png",避开字典序)。 */
internal fun sortedUgoiraFrames(folder: File): List<File> {
    return (folder.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
}

/**
 * 每帧延迟 ms:优先 metadata 的逐帧值(数量对得上才信),否则 [GifResponse.getDelay]
 * 单值兜底(兜底返回 60,永远 > 0)。
 */
internal fun ugoiraDelays(frameCount: Int, resp: GifResponse): List<Int> {
    val frames: List<FramesBean>? = resp.ugoira_metadata?.frames
    return if (frames != null && frames.size == frameCount) {
        frames.map { it.delay }
    } else {
        List(frameCount) { resp.delay }
    }
}

/** 按显式 [delaysMs] 逐帧编码(RIFE 补帧后延迟已减半,不再来自 metadata)。 */
internal fun encodeFramesToGif(
    files: List<File>,
    delaysMs: List<Int>,
    out: OutputStream,
    onProgress: (Int) -> Unit = {},
) {
    val encoder = AnimatedGifEncoder()
    encoder.start(out)
    encoder.setRepeat(0) // 无限循环
    val total = files.size
    for ((i, f) in files.withIndex()) {
        encoder.setDelay(delaysMs.getOrElse(i) { 60 })
        val bmp: Bitmap? = BitmapFactory.decodeFile(f.absolutePath)
        if (bmp != null) {
            encoder.addFrame(bmp)
            bmp.recycle()
        } else {
            Timber.tag(UGOIRA_PIPELINE_TAG).w("[UGOIRA] decode frame failed $f")
        }
        onProgress((i + 1) * 100 / total)
    }
    encoder.finish()
}
