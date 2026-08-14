package ceui.pixiv.ui.bulk

import android.content.Context
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
 * 帧序列」全链路。旧的 [ceui.lisa.fragments.FragmentSingleUgora] 把这套逻辑和
 * 具体 View / LocalBroadcast(PLAY_GIF) / 全局 Manager 回调 / MMKV flag 缠在一起;
 * 这里抽成一个不认识任何 View 的引擎。
 *
 * **工作不挂 Fragment 生命周期**:每条 ugoira 的 meta→下载→解压→编码跑在引擎自己的
 * [engineScope],结果放进 [jobs] 里的共享 [Deferred]。退出详情页只会取消「等待 + 观察进度」,
 * 底层任务继续把帧序列落缓存;再进来 [loadPlayableFrames] 直接命中缓存或 join 同一个还在跑的
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

    /** 补帧工作目录前缀(internal cacheDir 下),命名只此一处 —— [sweepStaleRifeWork] 靠它认领残留。 */
    private const val RIFE_WORK_PREFIX = "rife_work_"

    // 同时最多几条 ugoira 在跑「下载+编码」重活。实测不设限时 1MB 的 zip 因互抢带宽下了 39s。
    private const val MAX_CONCURRENT = 2

    // 划走后多久没人看就取消后台任务。来回滑动在宽限期内不会误杀。
    private const val ABANDON_GRACE_MS = 12000L

    // 引擎级 scope:SupervisorJob 让单条失败不拖垮别条;不随任何 Fragment 取消。
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 重活(下载+解压+编码)的并发闸门。meta / 缓存命中不占额度。
    private val gate = Semaphore(MAX_CONCURRENT)

    // RIFE 补帧是分钟级 GPU 任务,单独串行,不占 gate —— 否则一条在补帧,
    // 只差几秒编码的普通动图也要排队分钟级;且 GPU 上多个 ncnn 进程并行只会互相拖慢。
    private val rifeGate = Semaphore(1)

    // mp4 压制单独串行:硬件编码器实例数是设备级稀缺资源(不少机器只有 1~2 个 AVC 编码
    // 实例),并行开只会 configure 失败;而单条压制本来就只要一两秒。
    private val videoGate = Semaphore(1)

    /**
     * illustId -> 文件级互斥。播放引擎与保存链路([downloadUgoira])共写同一
     * zip/.part/解压目录,「边看边存同一条」无锁并发写会把 zip 持久写坏 —— 落盘一个
     * 损坏但非空的 zip 后,两条链路都因「已缓存」跳过重下,这条 ugoira 就坏死了。
     * 锁对象极小,map 只增不减的滞留量级无害。
     */
    private val illustFileLocks = ConcurrentHashMap<Int, Mutex>()

    internal fun fileLockFor(illustId: Int): Mutex =
        illustFileLocks.computeIfAbsent(illustId) { Mutex() }

    /** illustId -> 已就绪的可播放帧序列(内存快路径)。 */
    private val readyFramesCache = ConcurrentHashMap<Int, UgoiraFrames>()

    /**
     * illustId -> 可播帧序列广播。pipeline 只在**最终版**确定后发布一次(补帧成功=补帧版,
     * 否则=原速版)—— 中途不发,免得先播一段 12.5fps 的再跳到 50fps。播放器在此之前保持
     * 进度浮层,补帧进度照常走 [progressOf]。
     */
    private val framesFlows = ConcurrentHashMap<Int, MutableStateFlow<UgoiraFrames?>>()

    /** 观察某条 ugoira 的可播帧序列(进来立刻拿当前值;null = 还没有任何一版可播)。 */
    fun framesOf(illustId: Int): StateFlow<UgoiraFrames?> = framesFlowFor(illustId).asStateFlow()

    private fun framesFlowFor(illustId: Int): MutableStateFlow<UgoiraFrames?> =
        framesFlows.computeIfAbsent(illustId) { MutableStateFlow(readyFramesCache[illustId]) }

    /** 落一版可播帧序列:写内存缓存 + 广播给正在看的播放器。 */
    private fun publishFrames(id: Int, frames: UgoiraFrames) {
        readyFramesCache[id] = frames
        framesFlowFor(id).value = frames
    }

    /**
     * 本会话是否已确认 rife 在这台机器上根本跑不起来(Vulkan 初始化失败 / 进程非 0 退出 /
     * 输出帧数不符)。这类失败是设备/驱动级的,必然条条复发 —— 标记后直接当开关没开,
     * 别让用户每条动图都白等几分钟 GPU。
     *
     * **取消不算失败**(用户划走是正常路径),只有「探针还活着却没产出」才置位。
     * 开关重新切换或删模型([invalidateAll])会清掉,给用户一个显式的重试入口。
     */
    @Volatile
    private var rifeHardFailed = false

    /** illustId -> 进度广播,跨 Fragment 共享。 */
    private val progressFlows = ConcurrentHashMap<Int, MutableStateFlow<UgoiraProgress>>()

    // 下面三张表统一用 [lock] 保护:「查任务 + 改观察者计数 + 撤销/安排取消」要整体原子。
    private val lock = Any()
    private val jobs = HashMap<Int, Deferred<UgoiraFrames>>()   // illustId -> 正在跑的共享任务
    private val refs = HashMap<Int, Int>()              // illustId -> 当前观察者数
    private val cancelTimers = HashMap<Int, Job>()      // illustId -> 待触发的「划走取消」计时器

    /** 观察某条 ugoira 的加载进度(进来立刻拿当前值)。 */
    fun progressOf(illustId: Int): StateFlow<UgoiraProgress> = flowFor(illustId).asStateFlow()

    private fun flowFor(illustId: Int): MutableStateFlow<UgoiraProgress> =
        progressFlows.computeIfAbsent(illustId) {
            MutableStateFlow(UgoiraProgress(UgoiraPhase.FETCH_META))
        }

    /**
     * 保存链路([downloadUgoira])用:peek 已落盘的帧序列,优先补帧版,没有返回 null。
     * 变体选择是**确定性**的 —— 只看「当前开关 + 磁盘上有哪版」,不依赖本会话是否播过。
     * 只读文件系统,须在 IO 线程调用。
     */
    fun peekPlayableFrames(illust: IllustsBean): UgoiraFrames? {
        val ctx = Shaft.getContext()
        val useRife = Shaft.sSettings.isUgoiraRifeEnable() && RifeInterpolator.isAvailable(ctx)
        if (useRife) {
            readFramesDir(framesDirFor(ctx, illust, true), true)?.let { return it }
        }
        return readFramesDir(framesDirFor(ctx, illust, false), false)
    }

    /**
     * 保存链路的成品文件。[isVideo] 决定写进用户存储时用哪套 ext/mime;
     * [temporary] 为 true 时调用方**拷完必须删**(它只为这一次保存而生),
     * 为 false 说明这就是播放缓存里的那一份,删了会害得下次重压。
     */
    data class UgoiraExport(val file: File, val isVideo: Boolean, val temporary: Boolean)

    /**
     * 保存链路([ceui.lisa.download.IllustDownload].downloadGif / [downloadUgoira])用:
     * 把已落盘的帧序列变成一份可以直接拷进用户存储的成品,格式随「动图保存格式」设置走。
     *
     * - **mp4**:播放缓存里已经压好就直接给那一份(零成本);没压过就现压进同一个帧目录 ——
     *   顺带让下次播放也能硬解。压不出来(设备无编码器等)自动降级成 GIF,不让保存整个失败。
     * - **GIF**:现编一份临时文件(播放链路不再缓存 GIF)。
     *
     * 帧不在盘上返回 null,调用方回退到「下 zip → 解压 → 出片」的完整链路。
     * 阻塞,须在 IO 线程调用。
     */
    @JvmStatic
    fun exportForSave(illust: IllustsBean): UgoiraExport? {
        val frames = peekPlayableFrames(illust) ?: return null
        if (Shaft.sSettings.isUgoiraSaveAsMp4()) {
            exportVideo(illust, frames)?.let { return it }
        }
        val temp = File(Shaft.getContext().cacheDir, "ugoira_save_${illust.id}.gif")
        return try {
            BufferedOutputStream(FileOutputStream(temp)).use { bos ->
                encodeFramesToGif(frames.files, frames.delaysMs, bos)
            }
            Timber.tag(UGOIRA_LOG_TAG).i("[save] illust=%d 由帧序列编出临时 GIF (%d帧, %d bytes)", illust.id, frames.files.size, temp.length())
            UgoiraExport(temp, isVideo = false, temporary = true)
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            Timber.tag(UGOIRA_LOG_TAG).w(t, "[save] illust=%d 从帧序列编 GIF 失败,回退完整链路", illust.id)
            null
        }
    }

    /**
     * [exportForSave] 的「帧不在盘上就先把整条 pipeline 跑完」版本。
     *
     * 为什么需要它:老的保存链路(`PixivOperate.encodeGifV2`)只会产 GIF,用户设置成 mp4
     * 时,一条**没看过**的动图走到那里就会拿到 GIF —— 设置形同虚设。这里直接借播放
     * pipeline:下载 / 解压 / (按开关)补帧 / 压制一次做完,产物同时进播放缓存,下次打开秒开。
     *
     * 阻塞,可能要几秒到几分钟(下 zip + 可选补帧),**必须在 IO 线程调用**。
     * pipeline 失败返回 null,调用方回退老链路(出 GIF)。
     */
    @JvmStatic
    fun prepareAndExportForSave(illust: IllustsBean): UgoiraExport? {
        exportForSave(illust)?.let { return it }
        val ok = runCatching { runBlocking { loadPlayableFrames(illust) } }
            .onFailure { Timber.tag(UGOIRA_LOG_TAG).w(it, "[save] illust=%d 完整 pipeline 失败,回退老链路", illust.id) }
            .isSuccess
        return if (ok) exportForSave(illust) else null
    }

    /** [exportForSave] 的 mp4 分支。压不出来返回 null(调用方降级 GIF)。 */
    private fun exportVideo(illust: IllustsBean, frames: UgoiraFrames): UgoiraExport? {
        frames.video?.let {
            Timber.tag(UGOIRA_LOG_TAG).i("[save] illust=%d 直接复用播放缓存里的 mp4 (%d KB)", illust.id, it.length() / 1024)
            return UgoiraExport(it, isVideo = true, temporary = false)
        }
        if (UgoiraVideoEncoder.deviceUnsupported) return null
        // 压进帧目录(而不是临时文件):下次播放就能直接硬解,省一次压制。
        // runBlocking 只是把 encode 的 suspend 外壳拆掉 —— 调用方本来就在 IO 线程阻塞着。
        return runBlocking {
            fileLockFor(illust.id).withLock {
                readFramesDir(frames.dir, frames.interpolated)?.video?.let {
                    return@withLock UgoiraExport(it, isVideo = true, temporary = false)
                }
                val out = UgoiraVideoEncoder.encode(
                    File(frames.dir, UgoiraVideoEncoder.VIDEO_FILE_NAME),
                    frames.files, frames.delaysMs,
                )
                if (out != null) {
                    invalidate(illust.id) // 内存里那版 UgoiraFrames 还记着 video=null,清掉重取
                    UgoiraExport(out, isVideo = true, temporary = false)
                } else {
                    Timber.tag(UGOIRA_LOG_TAG).w("[save] illust=%d mp4 压制失败,本次保存降级成 GIF", illust.id)
                    null
                }
            }
        }
    }

    /** 纯内存 peek —— 已就绪的帧序列直接给,不碰文件系统。播放器主线程「秒开」专用(零 IO,免 ANR)。 */
    fun peekReadyInMemory(illustId: Int): UgoiraFrames? = readyFramesCache[illustId]

    /** 帧加载失败(疑似系统清了缓存目录)→ 清掉内存记录,下次走完整 pipeline 重新落盘。 */
    fun invalidate(illustId: Int) {
        readyFramesCache.remove(illustId)
        framesFlows.remove(illustId)
    }

    /**
     * 播放器报「帧序列在盘上却解不出来」时用:清内存记录 + 锁内删掉这版帧目录。
     * 「文件都在、数量也对、但内容坏」(断电截断类)时光清内存没用 —— 下一轮 pipeline 会
     * 磁盘命中同一批坏文件,自愈变成空转;只有删掉目录才是真重建。IO 线程调用。
     */
    suspend fun purgeBrokenFrames(illustId: Int, dir: File) {
        invalidate(illustId)
        fileLockFor(illustId).withLock {
            if (dir.deleteRecursively()) {
                Timber.tag(UGOIRA_LOG_TAG).w("[engine] illust=%d 已删除疑似损坏的帧目录 %s", illustId, dir.name)
            }
        }
    }

    /**
     * RIFE 补帧开关切换时调用:内存里记的是旧变体(原速/补帧)的 gif,全清,下次按新开关重取。
     * 顺带清掉 [rifeHardFailed] —— 用户手动切开关就是「再试一次」的意思。
     */
    @JvmStatic
    fun invalidateAll() {
        readyFramesCache.clear()
        framesFlows.clear()
        rifeHardFailed = false
    }

    /**
     * 清扫上个进程留下的补帧中间产物。[runPipeline] 的 finally 会删掉 `rife_work_<id>`
     * 整棵树,但那**要求进程还活着** —— 补帧是分钟级 + 满载 GPU,正是最容易被系统杀后台的
     * 窗口;一挂就在内部 cache 留下中间帧(4x 那轮峰值约为源帧的 8 倍,几百 MB 量级),
     * 而全项目没有任何东西会去收它。
     *
     * 冷启动调用即可(此时 [jobs] 必空,盘上的都是上次的残留)。仍然跳过正在跑的 illust,
     * 这样将来从别处(比如清缓存入口)调也不会误删活着的工作目录。
     *
     * fire-and-forget:自己开协程走 IO,任何异常吞掉 —— 清不掉缓存不该影响启动。
     */
    @JvmStatic
    fun sweepStaleRifeWork(context: Context) {
        engineScope.launch {
            runCatching {
                val rifeWork = context.cacheDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith(RIFE_WORK_PREFIX) }
                    .orEmpty()
                // 顺带收 gif cache 里的 frames_*.tmp:writeFramesDir 写到一半被杀留下的半成品
                // (十几 MB/条)。正主目录靠 readFramesDir 校验自愈,但 .tmp 只有同一条动图
                // 再次重写时才会被顺手删,否则无人认领。
                val tmpFrames = LegacyFile.gifCacheFolder(context).listFiles()
                    ?.filter {
                        it.isDirectory && it.name.startsWith(FRAMES_DIR_PREFIX) &&
                            it.name.endsWith(FRAMES_TMP_SUFFIX)
                    }
                    .orEmpty()
                var freed = 0L
                var removed = 0
                for (dir in rifeWork + tmpFrames) {
                    val id = dir.name.removePrefix(RIFE_WORK_PREFIX).removePrefix(FRAMES_DIR_PREFIX)
                        .takeWhile { it.isDigit() }.toIntOrNull()
                    if (id != null && synchronized(lock) { jobs.containsKey(id) }) continue
                    freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    if (dir.deleteRecursively()) removed++
                }
                // 再收 frames_<id>/video.mp4.tmp:压制途中被杀留下的半成品。正主目录还活着,
                // 上面那轮目录清扫不会认领它,而 readFramesDir 只认 video.mp4 —— 不收就没人收。
                // (同一条动图下次被打开时压制会先删 tmp,但没人再打开的就一直躺着。)
                val liveIds = synchronized(lock) { jobs.keys.toSet() }
                LegacyFile.gifCacheFolder(context).listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith(FRAMES_DIR_PREFIX) }
                    ?.forEach { dir ->
                        val id = dir.name.removePrefix(FRAMES_DIR_PREFIX)
                            .takeWhile { it.isDigit() }.toIntOrNull()
                        if (id != null && id in liveIds) return@forEach
                        val tmp = File(dir, UgoiraVideoEncoder.VIDEO_TMP_FILE_NAME)
                        if (tmp.isFile) {
                            freed += tmp.length()
                            if (tmp.delete()) removed++
                        }
                    }
                if (removed > 0) {
                    Timber.tag(UGOIRA_LOG_TAG)
                        .i("[sweep] 清掉 %d 个残留(rife_work / frames.tmp / video.mp4.tmp),回收 %d KB", removed, freed / 1024)
                }
            }.onFailure { Timber.tag(UGOIRA_LOG_TAG).w(it, "[sweep] 清扫残留补帧目录失败") }
        }
    }

    /**
     * 拿可播放帧序列(最终版:补帧开着就是补帧版)。命中缓存直接返回;否则复用/新建共享任务并 await。
     * 想「尽早开播」的调用方应改用 [framesOf] —— pipeline 会先 emit 原速版再 emit 补帧版。
     * **await 被取消(Fragment 退出)不取消底层任务**,只把观察者计数减一;划走够久没人看才回收。
     */
    suspend fun loadPlayableFrames(illust: IllustsBean): UgoiraFrames {
        val id = illust.id
        // 内存命中直接给,不做文件 stat —— 本方法在播放器主线程调用,不能碰文件系统
        // (免 disk-on-main / ANR)。文件真被系统清了,解码会失败,播放器 invalidate 后重来。
        readyFramesCache[id]?.let {
            Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableFrames] illust=%d 内存缓存命中 -> %s", id, it.dir.name)
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
    private fun acquireJob(illust: IllustsBean): Deferred<UgoiraFrames> = synchronized(lock) {
        val id = illust.id
        val count = (refs[id] ?: 0) + 1
        refs[id] = count
        cancelTimers.remove(id)?.cancel() // 有人(重新)进来了,别取消
        jobs[id]?.let { existing ->
            Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableFrames] illust=%d join 正在跑的任务,await(观察者=%d)", id, count)
            return@synchronized existing
        }
        Timber.tag(UGOIRA_LOG_TAG).i("[loadPlayableFrames] illust=%d 启动新任务,await", id)
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
        val d = jobs[id] ?: run {
            // 任务已终态且无人观察:进度流一并清掉(防 map 只增不减);下个观察者
            // 会拿到全新 flow(默认 FETCH_META),顺带不再看到上次残留的阶段标签
            progressFlows.remove(id)
            return@synchronized
        }
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

    /**
     * pipeline 唯一的收口:确保这版帧序列**有 mp4**,然后发布。
     *
     * 三条产出路径(磁盘命中补帧版 / 磁盘命中原速版且不值得补 / 完整跑完)都走这里,
     * 保证「发布出去的一定是能硬解播放的那一版」—— 播放器不必处理「先播帧序列、压好再切
     * mp4」的中途换版(会看到一次跳变)。
     */
    private suspend fun finishAndPublish(
        id: Int,
        frames: UgoiraFrames,
        flow: MutableStateFlow<UgoiraProgress>,
    ): UgoiraFrames {
        val final = ensureVideo(id, frames, flow)
        publishFrames(id, final)
        return final
    }

    /**
     * 帧序列 → mp4(缺就压)。压不出来就原样返回,播放回退到 [ceui.pixiv.ui.detail.FrameSequencePlayer]
     * —— 引入 mp4 不该让任何一条原本能播的动图变得不能播,所以除取消外任何 Throwable 都只降级。
     *
     * 握 per-illust 文件锁写目录(与保存链路互斥);调用方必须**不持有**该锁。
     */
    private suspend fun ensureVideo(
        id: Int,
        frames: UgoiraFrames,
        flow: MutableStateFlow<UgoiraProgress>,
    ): UgoiraFrames {
        if (frames.video != null || UgoiraVideoEncoder.deviceUnsupported) return frames
        return try {
            videoGate.withPermit {
                fileLockFor(id).withLock {
                    // 等锁期间别条链路可能刚压好同一版
                    readFramesDir(frames.dir, frames.interpolated)?.video?.let {
                        return@withLock frames.copy(video = it)
                    }
                    flow.value = UgoiraProgress(UgoiraPhase.ENCODE, 0)
                    Timber.tag(UGOIRA_LOG_TAG)
                        .i("[pipeline] illust=%d mp4 压制开始 (%d帧)", id, frames.files.size)
                    var lastQuarter = -1
                    val video = UgoiraVideoEncoder.encode(
                        File(frames.dir, UgoiraVideoEncoder.VIDEO_FILE_NAME),
                        frames.files, frames.delaysMs,
                    ) { pct ->
                        flow.value = UgoiraProgress(UgoiraPhase.ENCODE, pct)
                        if (pct / 25 != lastQuarter) {
                            lastQuarter = pct / 25
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d mp4 压制 %d%%", id, pct)
                        }
                    }
                    if (video != null) frames.copy(video = video) else frames
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Timber.tag(UGOIRA_LOG_TAG).w(t, "[pipeline] illust=%d mp4 压制异常,回退帧序列播放", id)
            frames
        }
    }

    private suspend fun runPipeline(illust: IllustsBean): UgoiraFrames {
        val id = illust.id
        val flow = flowFor(id)
        val t0 = System.currentTimeMillis()
        Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d ===== START =====", id)
        try {
            val ctx = Shaft.getContext()

            val useRife = Shaft.sSettings.isUgoiraRifeEnable() &&
                !rifeHardFailed &&
                RifeInterpolator.isAvailable(ctx)

            // 磁盘上已有想要的那一版帧序列:直接用。
            readFramesDir(framesDirFor(ctx, illust, useRife), useRife)?.let {
                val ready = finishAndPublish(id, it, flow)
                Timber.tag(UGOIRA_LOG_TAG).i(
                    "[pipeline] illust=%d 磁盘命中帧序列 -> %s (%d帧, mp4=%b),直接返回",
                    id, ready.dir.name, ready.files.size, ready.video != null,
                )
                return ready
            }

            // 想要补帧版但盘上只有原速版:延迟就躺在 delays.txt 里,先判「值不值得补」。
            // 帧率已高 / 帧数超限的动图**永远不会产出 _rife 目录**,而成功路径的
            // [discardIntermediates] 已把 zip/解压帧删了 —— 不在这里提前判掉的话,这类动图
            // 每次冷启动都会白白重下整个 zip、解压、再丢弃,循环往复。
            if (useRife) {
                readFramesDir(framesDirFor(ctx, illust, false), false)?.let { base ->
                    if (!RifeInterpolator.worthInterpolating(base.delaysMs)) {
                        val ready = finishAndPublish(id, base, flow)
                        Timber.tag(UGOIRA_LOG_TAG).i(
                            "[pipeline] illust=%d 磁盘命中原速帧序列且不值得补帧(%d帧,最小延迟%dms,mp4=%b),直接返回",
                            id, ready.files.size, ready.delaysMs.min(), ready.video != null,
                        )
                        return ready
                    }
                }
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

            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 申请文件锁/并发额度(gate 空闲=%d)…", id, gate.availablePermits)
            val unzipFolder = LegacyFile.gifUnzipFolder(ctx, illust)
            // 补帧工作目录整棵树由 finally 兜底删除。这个 finally **必须在文件锁里面**:放到锁外的
            // 话,本轮取消后下一轮 pipeline 一拿到锁就开始往 rife_work_<id> 里拷帧,而本轮的
            // deleteRecursively 正在删同一棵树 —— 下一轮补帧会因「输出帧数不符」失败,还会被
            // [rifeHardFailed] 误判成设备级故障,把整个会话的补帧静默关掉。
            val rifeWorkRoot = File(ctx.cacheDir, RIFE_WORK_PREFIX + id)
            var result: UgoiraFrames? = null
            fileLockFor(id).withLock {
                val zipFile = LegacyFile.gifZipFile(ctx, illust)
                try {
                    gate.withPermit {
                        Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 拿到并发额度,开始下载/解压", id)
                        coroutineContext.ensureActive()

                        // 2/4 下载 zip
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

                        // 3/4 解压。失败大概率 zip 本身坏了:删 zip 再抛,下次进来重新下载(自愈)。
                        val expected = resp.ugoira_metadata?.frames?.size ?: 0
                        val onDisk = unzipFolder.listFiles()?.count { it.isFile } ?: 0
                        if (onDisk == 0 || (expected > 0 && onDisk != expected)) {
                            if (onDisk > 0) unzipFolder.listFiles()?.forEach { runCatching { it.delete() } }
                            flow.value = UgoiraProgress(UgoiraPhase.EXTRACT)
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 开始 (磁盘有 %d 帧,期望 %d)", id, onDisk, expected)
                            try {
                                ZipUtils.unzipFile(zipFile, unzipFolder)
                            } catch (t: Throwable) {
                                runCatching { zipFile.delete() }
                                throw t
                            }
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 完成 (%d 帧)", id, unzipFolder.listFiles()?.size ?: 0)
                        } else {
                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3/4] EXTRACT 跳过(已解压 %d 帧)", id, onDisk)
                        }
                    }
                    coroutineContext.ensureActive()

                    val srcFrames = sortedUgoiraFrames(unzipFolder)
                    val srcDelays = ugoiraDelays(srcFrames.size, resp)
                    if (srcFrames.isEmpty()) throw IllegalStateException("no frames extracted for illust=$id")

                    // 原速帧序列落盘。pixiv 解压出来就是 JPEG,writeFramesDir 走纯复制、不重编码,
                    // 几十 ms 就好。**不在这里发布** —— 用户要的是「补帧完成才动」,不要先播一段
                    // 低帧率的再跳变。它的作用是持久缓存 + 补帧失败/不适用时的回退版本。
                    val baseDir = framesDirFor(ctx, illust, false)
                    val cachedBase = readFramesDir(baseDir, false)
                    val baseFrames = cachedBase
                        ?: writeFramesDir(baseDir, srcFrames, srcDelays)
                        ?: throw IllegalStateException("write base frames failed for illust=$id")
                    result = baseFrames
                    Timber.tag(UGOIRA_LOG_TAG).i(
                        "[pipeline] illust=%d 原速帧序列%s(%d帧,一圈%dms) 耗时 %dms",
                        id, if (cachedBase != null) "复用盘上已有" else "落盘",
                        baseFrames.files.size, baseFrames.totalMs, System.currentTimeMillis() - t0,
                    )

                    // 3.5/4 RIFE 补帧(可选)。任何失败都保留上面的原速版,播放不因补帧挂掉 ——
                    // 这个承诺必须覆盖到**转存阶段的异常**:rife 输出的 PNG 被截断(磁盘满是
                    // 现实触发源,补帧中间产物正是吃磁盘大户)时,writeFramesDir 的 decode 会
                    // 直接抛,不兜住的话原速版明明已落盘,整条 pipeline 却报 FAILED 给用户看
                    // 重试按钮。除取消外,补帧侧任何 Throwable 都只降级、不外抛。
                    if (useRife && RifeInterpolator.worthInterpolating(srcDelays)) {
                        try {
                            rifeGate.withPermit {
                                flow.value = UgoiraProgress(UgoiraPhase.INTERPOLATE, 0)
                                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 开始 (%d帧)", id, srcFrames.size)
                                var lastQuarter = -1
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
                                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 完成 → %d帧", id, rife.delaysMs.size)
                                    coroutineContext.ensureActive()
                                    // 4/4 转存补帧结果为播放用 JPEG 序列(RIFE 出的是 PNG,280 帧要 50~110MB,必须转)
                                    gate.withPermit {
                                        flow.value = UgoiraProgress(UgoiraPhase.ENCODE, 0)
                                        Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [4/4] 转存补帧帧序列开始", id)
                                        var lastQ = -1
                                        val rifeDir = framesDirFor(ctx, illust, true)
                                        val converted = writeFramesDir(
                                            rifeDir, sortedUgoiraFrames(rife.framesDir), rife.delaysMs,
                                        ) { pct ->
                                            flow.value = UgoiraProgress(UgoiraPhase.ENCODE, pct)
                                            if (pct / 25 != lastQ) {
                                                lastQ = pct / 25
                                                Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [4/4] 转存 %d%%", id, pct)
                                            }
                                        }
                                        if (converted != null) {
                                            result = converted
                                            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 补帧帧序列就绪(%d帧,一圈%dms)", id, converted.files.size, converted.totalMs)
                                        }
                                    }
                                } else if (pipelineJob?.isActive != false) {
                                    rifeHardFailed = true
                                    Timber.tag(UGOIRA_LOG_TAG).w("[pipeline] illust=%d [3.5/4] INTERPOLATE 失败,本会话不再尝试补帧,保留原速版", id)
                                } else {
                                    Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 被取消,保留原速版", id)
                                }
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // 不置 rifeHardFailed:rife 本身可能是好的,坏的是转存(磁盘满/坏 PNG),
                            // 那是内容/环境级问题,下条动图未必复发。
                            Timber.tag(UGOIRA_LOG_TAG).w(t, "[pipeline] illust=%d 补帧/转存异常,保留原速版继续", id)
                        }
                    } else if (useRife) {
                        Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d [3.5/4] INTERPOLATE 跳过(帧率已高或帧数超限)", id)
                    }

                    // 走到这里 = 本轮正常收尾(原速版必在,补帧成败已定)。异常路径不经过这;
                    // 取消(划走 abandon)也跳过 —— 与 [discardIntermediates] 的「只在成功路径
                    // 调用」约定一致:取消在补帧中段时,重进还要拿 zip/解压帧接着补,删了就得重下。
                    if (coroutineContext[Job]?.isActive != false) {
                        discardIntermediates(id, zipFile, unzipFolder)
                    }
                } finally {
                    rifeWorkRoot.deleteRecursively()
                }
            }
            val r = result ?: throw IllegalStateException("no frames produced for illust=$id")
            // 只在最终版确定后发布一次:补帧成功就是补帧版,补帧失败/不适用/开关关着就是原速版。
            // 中途不发布 —— 播放器要么不动,要么直接以最终帧率动起来。压制 mp4 也在发布之前
            // 做完(finishAndPublish),免得先播一版软解帧序列再切硬解、看到一次跳变。
            val ready = finishAndPublish(id, r, flow)
            Timber.tag(UGOIRA_LOG_TAG).i(
                "[pipeline] illust=%d ===== SUCCESS ===== %s (%d帧, mp4=%b) 耗时 %dms",
                id, ready.dir.name, ready.files.size, ready.video != null,
                System.currentTimeMillis() - t0,
            )
            return ready
        } catch (c: CancellationException) {
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d 已取消(划走无人看 / 进程回收) 耗时 %dms", id, System.currentTimeMillis() - t0)
            throw c
        } catch (t: Throwable) {
            Timber.tag(UGOIRA_LOG_TAG).e(t, "[pipeline] illust=%d ===== FAILED ===== 耗时 %dms", id, System.currentTimeMillis() - t0)
            throw t
        } finally {
            val myJob = coroutineContext[Job]
            synchronized(lock) {
                if (jobs[id] === myJob) {
                    jobs.remove(id)
                    cancelTimers.remove(id)?.cancel()
                }
                if ((refs[id] ?: 0) == 0) progressFlows.remove(id)
            }
            Timber.tag(UGOIRA_LOG_TAG).i("[pipeline] illust=%d END", id)
        }
    }

    /**
     * 播放用帧序列已落盘 → zip 和解压帧就是死重量,趁还握着 per-illust 文件锁删掉。
     *
     * `gifCacheFolder` 没有任何自动淘汰(只有设置页一个手动按钮),而一条动图会在里面留下
     * 多份:zip、上百张解压帧、播放用帧序列。ugoira 的 zip 装的是 JPEG、基本没压缩,所以前两份
     * 加起来是笔实打实的开销 —— 看几十条就是 GB 级。pipeline 本来就有「帧序列不在盘上就重下
     * 重解」的自愈路径,删掉不破坏幂等性。
     *
     * 必须在文件锁内调用,且只在成功路径调用 —— 失败/取消时留着中间产物,下次进来接着用。
     */
    internal fun discardIntermediates(id: Int, zipFile: File, unzipFolder: File) {
        val freed = zipFile.length() +
            (unzipFolder.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
        runCatching { zipFile.delete() }
        runCatching { unzipFolder.deleteRecursively() }
        Timber.tag(UGOIRA_LOG_TAG)
            .i("[pipeline] illust=%d 清掉中间产物(zip + 解压帧),回收 %d KB", id, freed / 1024)
    }

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

// ── 播放用帧序列 ─────────────────────────────────────────────────────────────
// 播放不再走 GIF:GIF 在 Glide 里是纯 Java 软解 LZW(实测 490x445 单帧约 25ms),4x 补帧的
// 20ms 预算根本喂不饱它,只能把动作拖慢。第一步改成播 JPEG 帧序列(native 解码快数倍);
// 现在再进一步,帧序列落盘后**顺手压一个 H.264 mp4**([UgoiraVideoEncoder]),播放优先走
// 硬件解码([ceui.pixiv.ui.detail.UgoiraVideoPlayer]) —— 软解 JPEG 在补帧后的 20ms 预算下
// 仍会因降频跟不上而丢帧,硬解不会。JPEG 帧序列保留:压制失败/设备不支持时的回退版本,
// 也是「保存为 GIF」的帧源。GIF 只在用户点「保存」时才编。

/**
 * 可播放的一版 ugoira。[files] 与 [delaysMs] 一一对应,供 `FrameSequencePlayer` 使用;
 * [video] 非空时优先走 `UgoiraVideoPlayer` 硬解播放(同一份内容的 mp4 版本)。
 */
data class UgoiraFrames(
    val dir: File,
    val files: List<File>,
    val delaysMs: List<Int>,
    val interpolated: Boolean,
    /** 同目录下压好的 mp4(`video.mp4`);null = 还没压 / 压不出来,回退帧序列播放。 */
    val video: File? = null,
) {
    /** 一圈总时长 —— 补帧前后必须相等,播放器靠它做无缝升级;也是 mp4 的循环长度。 */
    val totalMs: Int get() = delaysMs.sum()
}

private const val FRAMES_DIR_PREFIX = "frames_"
private const val FRAMES_TMP_SUFFIX = ".tmp"
private const val FRAMES_DELAY_FILE = "delays.txt"
private const val FRAME_JPEG_QUALITY = 92

/** 帧序列目录:`frames_<id>` / `frames_<id>_rife`,和 gif 变体一样按开关分开存。 */
internal fun framesDirFor(ctx: Context, illust: IllustsBean, interpolated: Boolean): File =
    File(
        LegacyFile.gifCacheFolder(ctx),
        FRAMES_DIR_PREFIX + illust.id + if (interpolated) "_rife" else "",
    )

/**
 * 读已落盘的帧序列。delays.txt 行数与 jpg 数量对不上就当没有(上次写到一半被杀),
 * 返回 null 让调用方重做 —— 和 zip/解压那套完整性校验同一个思路。
 */
internal fun readFramesDir(dir: File, interpolated: Boolean): UgoiraFrames? {
    val delayFile = File(dir, FRAMES_DELAY_FILE)
    if (!delayFile.isFile) return null
    val delays = runCatching {
        delayFile.readLines().mapNotNull { it.trim().toIntOrNull() }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".jpg") }
        .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
    if (files.size != delays.size) return null
    // mp4 是可选增强:不在就是 null,回退帧序列播放(老缓存目录天然如此)。
    val video = File(dir, UgoiraVideoEncoder.VIDEO_FILE_NAME).takeIf { it.isFile && it.length() > 0 }
    return UgoiraFrames(dir, files, delays, interpolated, video)
}

/**
 * 把 [srcFrames] 转存成播放用的 JPEG 序列 + delays.txt。
 *
 * 源已经是 JPEG(pixiv 解压帧)时**直接复制、不重编码** —— 原速那一档几乎零成本;
 * 只有 RIFE 输出的 PNG 才需要解码再压 JPEG(PNG 存 280 帧要 50~110MB,必须转)。
 *
 * 转码并行 [FRAME_CONVERT_PARALLELISM] 路(实测 280 帧单线程约 4s)。先写 `.tmp` 目录再整体
 * rename,中途被杀不会留下半套骗过 [readFramesDir]。
 */
internal suspend fun writeFramesDir(
    target: File,
    srcFrames: List<File>,
    delaysMs: List<Int>,
    onProgress: (Int) -> Unit = {},
): UgoiraFrames? {
    if (srcFrames.isEmpty() || srcFrames.size != delaysMs.size) return null
    val tmp = File(target.parentFile, target.name + FRAMES_TMP_SUFFIX)
    tmp.deleteRecursively()
    tmp.mkdirs()
    val done = java.util.concurrent.atomic.AtomicInteger()
    val total = srcFrames.size
    try {
        coroutineScope {
            val sem = Semaphore(FRAME_CONVERT_PARALLELISM)
            srcFrames.mapIndexed { i, src ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        coroutineContext.ensureActive()
                        val dst = File(tmp, "%08d.jpg".format(i))
                        if (src.extension.equals("jpg", true) || src.extension.equals("jpeg", true)) {
                            src.copyTo(dst, overwrite = true)
                        } else {
                            val bmp = BitmapFactory.decodeFile(src.absolutePath)
                                ?: throw IllegalStateException("decode frame failed: $src")
                            try {
                                FileOutputStream(dst).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, out)
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                        onProgress(done.incrementAndGet() * 100 / total)
                    }
                }
            }.forEach { it.await() }
        }
        File(tmp, FRAMES_DELAY_FILE).writeText(delaysMs.joinToString("\n"))
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("rename frames .tmp → ${target.name} failed")
    } catch (t: Throwable) {
        tmp.deleteRecursively()
        throw t
    }
    return readFramesDir(target, target.name.endsWith("_rife"))
}

/** 帧转码并行度。IO + native 编解码混合,4 路足以吃满而不炸内存(每路峰值 2 张 Bitmap)。 */
private const val FRAME_CONVERT_PARALLELISM = 4

/** 全局调色板的训练样本预算(像素数)。约 360KB buffer,一次训练替代 N 次逐帧训练。 */
private const val PALETTE_SAMPLE_PIXELS = 120_000

/** 最多抽几帧参与全局调色板训练 —— 均匀铺满整条动图,不是只看开头。 */
private const val PALETTE_SAMPLE_FRAMES = 8

/** 样本少于这个数就不值得建全局调色板(帧太小,逐帧量化本来也不慢)。 */
private const val PALETTE_MIN_SAMPLE_PIXELS = 1_000

/**
 * 帧内取样的跳跃步长(大素数)。**不能用线性步长** `i*nPix/take` —— 它在 take 整除 nPix
 * 时退化成精确的固定步长,一旦该步长与图宽有大公约数就只采到那么几列像素,颜色分布严重
 * 失真。实测(600x600、40 帧合成动画)线性取址在 take=1500 时步长恰为 240、gcd(240,600)=120,
 * 只覆盖 5 个列,RMSE 从 4.4 劣化到 11.7;换成素数跳跃后各档稳定在 4.3~4.9。
 *
 * `i * 104729 % nPix` 在 gcd(104729, nPix)=1 时能遍历全部像素位置,取前 take 个即均匀散布。
 * 104729 是素数且不含 2/3/5 因子,正常图片的 w*h 不可能含它作因子,互质恒成立。
 */
private const val PALETTE_STRIDE_PRIME = 104_729L

/**
 * 从整条动图里均匀抽样像素,喂给 [AnimatedGifEncoder.setGlobalPalette] 训练全局调色板。
 * 返回 BGR 三元组数组(与编码器内部 `pixels` 布局一致);样本不足或帧全解码失败时返回
 * null,调用方退回原来的逐帧调色板。
 *
 * 抽样策略两处都有意为之:
 *  - **帧维度**均匀取 [PALETTE_SAMPLE_FRAMES] 张铺满整条动图,而不是只看开头几帧 ——
 *    后半段才出现的颜色也要进调色板,否则那几帧会被映射到差很远的近似色。
 *  - **帧内**用 [PALETTE_STRIDE_PRIME] 素数跳跃取址,避开「固定步长与图宽有公约数 →
 *    只采到某几列」的失真(NeuQuant 内部用素数步长也是为了这个)。
 *
 * 成本是额外解码最多 8 帧(600x600 JPEG 约几十 ms),换掉 N-1 次 NeuQuant.learn()。
 */
private fun buildGlobalPalette(files: List<File>): ByteArray? {
    if (files.isEmpty()) return null
    val pickCount = minOf(files.size, PALETTE_SAMPLE_FRAMES)
    val perFrame = PALETTE_SAMPLE_PIXELS / pickCount
    val buf = ByteArray(PALETTE_SAMPLE_PIXELS * 3)
    var w = 0
    for (p in 0 until pickCount) {
        val file = files[(p.toLong() * files.size / pickCount).toInt()]
        val bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
        try {
            val nPix = bmp.width * bmp.height
            if (nPix <= 0) continue
            val row = IntArray(nPix)
            bmp.getPixels(row, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val take = minOf(perFrame, nPix)
            for (i in 0 until take) {
                val px = row[(i.toLong() * PALETTE_STRIDE_PRIME % nPix).toInt()]
                buf[w++] = (px and 0xFF).toByte()           // B
                buf[w++] = ((px shr 8) and 0xFF).toByte()   // G
                buf[w++] = ((px shr 16) and 0xFF).toByte()  // R
            }
        } finally {
            bmp.recycle()
        }
    }
    if (w / 3 < PALETTE_MIN_SAMPLE_PIXELS) return null
    return if (w == buf.size) buf else buf.copyOf(w)
}

/**
 * 把帧序列的真实延迟量化成 GIF 能表达的延迟(必为 10ms 的整数倍)。
 *
 * GIF 的延迟单位是 cs(10ms),且 [AnimatedGifEncoder.setDelay] 是**截断**取整 ——
 * 逐帧独立取整会让一圈总时长整体漂移(25ms 全被截成 20ms 就是快 20%)。所以按
 * **累积时间轴**量化:第 i 帧的结束时刻四舍五入到 10ms,差值才是本帧延迟,单帧误差
 * ≤5ms 且不累积,一圈总时长与帧序列(和 mp4)一致。
 *
 * 有了这一层,补帧产出的延迟就可以是任意毫秒数([RifeInterpolator.splitDelays] 因此
 * 不必再自己对齐 10ms,那会让插出来的帧偏离正确时刻),容器的粒度限制留在容器这一层。
 */
internal fun gifFrameDelaysMs(delaysMs: List<Int>, frameCount: Int): List<Int> {
    val out = ArrayList<Int>(frameCount)
    var elapsedMs = 0   // 帧序列的真实累积时刻
    var assignedMs = 0  // 已写进 GIF 的累积时刻(必为 10 的倍数)
    for (i in 0 until frameCount) {
        elapsedMs += delaysMs.getOrElse(i) { 60 }
        val target = (elapsedMs + 5) / 10 * 10
        // 两帧落进同一个 10ms 桶时给 10ms 兜底:GIF 的 0 延迟会被看图器当「尽快播」处理,
        // 反而更糟。只可能出现在原始 metadata 就 <10ms 的病态动图上。
        val frameDelay = (target - assignedMs).coerceAtLeast(10)
        assignedMs += frameDelay
        out.add(frameDelay)
    }
    return out
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
    // 全局调色板:消掉逐帧调色板导致的帧间闪烁,同时省掉 N-1 次 NeuQuant 训练。
    // 抽样失败(帧全解不开)就退回逐帧,不影响出片。
    buildGlobalPalette(files)?.let { encoder.setGlobalPalette(it) }
    val total = files.size
    val gifDelays = gifFrameDelaysMs(delaysMs, files.size)
    for ((i, f) in files.withIndex()) {
        encoder.setDelay(gifDelays[i])
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
