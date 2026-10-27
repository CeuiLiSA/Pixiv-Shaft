package ceui.lisa.update

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import ceui.lisa.BuildConfig
import ceui.lisa.http.GithubProxy
import ceui.lisa.http.Retro
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内自托管的 APK 下载器，替代系统的 [android.app.DownloadManager]。
 *
 * <p>进程级服务，由 [ceui.pixiv.services.ServicesProvider] 持有。构造廉价：Gson、
 * OkHttpClient 都 `by lazy`，冷启动只在 [install] 里读一条 MMKV、stat 几个文件。
 *
 * <p><b>为什么不用系统下载器</b>：DownloadManager 跑在 `com.android.providers.downloads`
 * 进程里，请求的 UID 不是本应用。用「按应用白名单」的代理（Clash / Surge 那类 per-app
 * 规则）时，下载器进程不在白名单里，APK 流量会绕过代理直连出去——用户看到的是
 * 「更新包下载不走代理」，运气不好还会被墙在半路。把下载搬回本进程，走 OkHttp，
 * 请求就落在应用自己的 UID 上，和白名单语义一致。
 *
 * <p><b>断点续传</b>：按 HTTP Range 分片，每片写自己的 `.part` 文件。分片文件的
 * 长度就是「已经拿到多少」，**不需要额外持久化进度**——进程被系统杀掉时文件还在，
 * 下次启动按长度接着要 Range 即可。分片边界、URL、ETag 这些「怎么切」的信息才
 * 落 MMKV，见 [Record]。
 *
 * <p><b>进程级 = 不主动续传</b>：本对象随进程存活，进程被杀下载就停。重启后只把
 * 状态恢复成 [Phase.PAUSED]，**不会自动开跑**，由用户在 sheet 里点「继续下载」。
 *
 * <p><b>加速地址变更</b>：分片下载期间每隔约 1 秒比对一次当前 GitHub 加速前缀，
 * 与开工时记录的不一致就**无条件取消**（分片删除、状态置失败）。换源之后旧分片的
 * 字节无法证明与新源同源，续传没有意义。
 */
class ApkDownloadManager(private val app: Application) {

    companion object {
        private const val TAG = "ApkDownload"

        /** 默认并发分片数。 */
        const val DEFAULT_THREADS = 8

        /** 小于这个体积不分片：一条连接就够了，切片只会多几次请求开销。 */
        private const val MIN_CHUNK_BYTES = 1L * 1024 * 1024

        private const val BUFFER_SIZE = 64 * 1024

        /** 拼装时的拷贝块：见 [assemble] 的注释。 */
        private const val ASSEMBLE_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PROXY_CHECK_INTERVAL_MS = 1_000L

        /** 开局分片的读超时；被切出来的子段按「父段是否过半」逐代减半。 */
        private const val BASE_READ_TIMEOUT_MS = 20_000L

        /** 减半的下限：再低会把「网络慢但没死」的连接也误杀。 */
        private const val MIN_READ_TIMEOUT_MS = 2_000L

        /**
         * 收尾阶段的「静默看门狗」：整体下到 80% 之后，**在飞的连接**如果超过下面这些时长
         * 一个字节都没收到，就直接掐掉重连。
         *
         * OkHttp 的读超时是建 client 时定死的，改不了已经在跑的 call；而收尾阶段最怕的
         * 恰恰是那几条早就建好、还挂着 20 秒超时的老连接——它一断流，整单就卡在 99%
         * 干等 20 秒。看门狗在应用层按「最后收字节的时间」自己判，跟 call 是哪个 client
         * 建出来的无关，所以对已在飞的连接同样生效。
         */
        private const val STALL_WATCH_INTERVAL_MS = 500L
        private const val STALL_ALLOW_80 = 5_000L
        private const val STALL_ALLOW_90 = 3_000L
        private const val STALL_ALLOW_95 = 2_000L

        /**
         * 单个分片的最大尝试次数。
         *
         * 真机实测：8 条连接里偶尔有一条被中间设备掐住（不 FIN、也不再送数据），
         * 读超时后整单失败——可另外 7 段其实已经下完，只差这一小截。
         * 分片本身是可续的，重连一次就能补上，没必要让用户看到「下载中断」。
         */
        private const val MAX_SEGMENT_ATTEMPTS = 3

        private const val KEY_RECORD = "update_apk_download_v2"
        private const val APK_FILE_NAME = "shaft-update.apk"
        private const val PART_FILE_SUFFIX = ".part"

        /** 老版本 AppUpdateChecker 存系统下载器 id 的键，只用于一次性迁移清理。 */
        private const val LEGACY_KEY_DOWNLOAD_ID = "update_download_id"
        private const val LEGACY_KEY_DOWNLOAD_TAG = "update_download_tag"

        private const val STATUS_RUNNING = "RUNNING"
        private const val STATUS_PAUSED = "PAUSED"
        private const val STATUS_FAILED = "FAILED"
        private const val STATUS_COMPLETED = "COMPLETED"
    }

    enum class Phase {
        /** 没有这个版本的下载记录。 */
        IDLE,
        RUNNING,

        /**
         * 分片都到齐了，正在拼成单个 APK。
         *
         * 45MB 走一遍外置存储的读写，真机上是几百毫秒到两秒——单独一个状态，
         * 免得用户看到「100% 却迟迟不出现安装按钮」以为卡死了。
         */
        MERGING,

        /** 有断点、可以接着下（用户暂停 / 进程被杀 / 可重试的网络中断）。 */
        PAUSED,

        /** 出错；[State.resumable] 为 false 时断点已作废，只能从头重试。 */
        FAILED,
        COMPLETED,
    }

    enum class Reason {
        NETWORK,

        /** 断点无法继续：服务器不支持 Range、文件大小/ETag 变了、加速地址变了。 */
        NOT_RESUMABLE,
        PROXY_CHANGED,
        IO,
        UNKNOWN,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val tag: String? = null,
        val versionName: String? = null,
        val totalBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val resumable: Boolean = false,
        val reason: Reason? = null,
        /** 分片总数（= 开几条连接）。 */
        val threads: Int = 0,
        /** 还在收数据的连接数；分片陆续下完会一路减到 0。 */
        val activeThreads: Int = 0,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0
            else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * 一个分片。[end] 为 -1 表示「长度未知，读到 EOF 为止」（服务器没给 Content-Length
     * 且不支持 Range 时只剩这一种下法），此时 [size] 没有意义。
     *
     * [end] 会被「抢片」改写：空闲连接把某段**剩余的**对半切开，自己拿走后半段。
     * 所以它必须是可变的 volatile 字段——正在下这条的连接每读一块都要重新看一眼边界。
     */
    private class Chunk(
        val start: Long,
        @Volatile var end: Long,
        /** 可不可以被空闲连接抢走一半。整文件单连接的那种片必须为 false。 */
        val splittable: Boolean = true,
        /**
         * 由「抢片」切出来的尾段。
         *
         * 这种片通常很小、又是收尾的关键路径：它一断流，整单就卡在 99% 等它。
         * 所以它用更短的读超时（见 [Chunk.readTimeoutMs]），断流了尽快换连接重来。
         */
        val stolen: Boolean = false,
        /**
         * 这条连接「多久没收到一个字节」就算断，毫秒。
         *
         * 开局 8 个等分大段是 [BASE_READ_TIMEOUT_MS]；**已经下过半的段被切走时**，
         * 切出来的子段再减半，一路指数递减到 [MIN_READ_TIMEOUT_MS]。
         *
         * **不落盘**（`@Transient`）：续传时可能已经换了网络，上次那套「越下越没耐心」
         * 的档位没有参考价值，一律退回默认值重新开始。
         */
        @Transient
        var readTimeoutMs: Long = BASE_READ_TIMEOUT_MS,
    ) {
        val bounded: Boolean get() = end >= 0L
        val size: Long get() = if (bounded) end - start + 1 else -1L
    }

    /**
     * 落 MMKV 的下载计划。**只记「怎么切」和「切到哪」不记字节**——字节在 .part 文件里，
     * 文件长度就是进度，进程被杀也不会出现「记了进度但没写盘」的错位。
     */
    private data class Record(
        var tag: String = "",
        var versionName: String = "",
        /** 整个 release 的 JSON：sheet 关掉后要能凭它重新打开（banner 跳回）。 */
        var releaseJson: String = "",
        /** 实际请求的 URL（已插过加速前缀）。 */
        var url: String = "",
        /** 开工时的加速前缀，用来判断中途是否换源。 */
        var proxyPrefix: String = "",
        var totalBytes: Long = 0L,
        var etag: String? = null,
        var lastModified: String? = null,
        var rangeSupported: Boolean = false,
        /**
         * 分片计划。**会被追加**：空闲连接抢到某段的后半截时，新片追加在表尾。
         * 因此表序不等于起始偏移序，拼装时要按 [Chunk.start] 排序。
         *
         * 用写时复制表：抢片时追加、下载线程同时按 index 读，普通 ArrayList
         * 在「边读边 add」下没有可见性保证（极端情况下读到 null）。
         */
        var segments: MutableList<Chunk> = CopyOnWriteArrayList(),
        var status: String = STATUS_PAUSED,
        /** 下载完成但当时没有 sheet 订阅，需要在前台发 banner。 */
        var bannerPending: Boolean = false,
    )

    private class NotResumableException(message: String) : IOException(message)

    private class ProxyChangedException : IOException("GitHub 加速地址已变更")

    private val gson by lazy { Gson() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val client: OkHttpClient by lazy { clientFor(BASE_READ_TIMEOUT_MS) }

    /**
     * 按读超时缓存客户端。分片的超时只有 20s / 10s / 5s / 2.5s / 2s 这几档
     * （见 [Chunk.readTimeoutMs] 的逐代减半），每个档位建一次就够了。
     *
     * 读超时是「多久没收到一个字节」而不是「整段多久下完」，所以调小不会误杀
     * 真正在慢慢送数据的连接；能触发它的基本就是断流死了的连接。
     */
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientFor(readTimeoutMs: Long): OkHttpClient =
        clients.getOrPut(readTimeoutMs) { buildClient(readTimeoutMs) }

    private fun buildClient(readTimeoutMs: Long): OkHttpClient =
        Retro.getLogClient()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 默认 maxRequestsPerHost 是 5，8 个分片会有 3 条一直排队，
            // 「8 线程」名不副实。这里放开到分片数。
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = DEFAULT_THREADS
                    maxRequestsPerHost = DEFAULT_THREADS
                }
            )
            .build()

    private val banner = UpdateBanner(app, this)

    private val installed = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * 每次「开始 / 暂停 / 取消」自增。异步任务在写状态前先比对 token，
     * 避免上一轮的收尾（比如把状态改成失败）盖掉这一轮刚开始的状态。
     * 读写跨主线程与 IO 线程，必须是 volatile。
     */
    @Volatile
    private var runToken = 0

    /** 正在飞的请求，暂停 / 取消时统一 cancel —— `execute()` 是阻塞的，不 cancel 停不下来。 */
    private val calls = ConcurrentHashMap<Int, Call>()

    private val proxyCheckedAt = AtomicLong(0L)

    /**
     * 正在看下载状态的 sheet 数量。用计数而不是布尔：banner 跳回来时可能盖在
     * 已经打开的那个 sheet 上，任何一个关闭都不该让另一个失去「有人看着」的身份。
     */
    private val sheetCount = AtomicInteger(0)

    private val sheetAttached: Boolean
        get() = sheetCount.get() > 0

    private var cachedReleaseJson: String? = null
    private var cachedRelease: GitHubRelease? = null

    // ---------------------------------------------------------------- 生命周期

    /**
     * 进程启动时调用一次（`Shaft.onCreate`）。只读 MMKV + stat 几个文件，可以在主线程跑。
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        cancelLegacySystemDownload()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // 「下载完成但当时没有 sheet」的 banner 要等到前台才发；
                // 冷启动时这里也兜住「上次已完成、进程后来被杀」的情形。
                banner.onForeground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        restore()
    }

    /**
     * 一次性迁移：老版本把 APK 交给系统 DownloadManager，那条记录留在系统里，
     * 升级之后它可能还在跑——跑的正是这次要消掉的「绕过应用 UID / 代理白名单」的请求。
     * 按老版本存下的 id 删掉，删完把键清掉，之后永不再执行。
     */
    private fun cancelLegacySystemDownload() {
        runCatching {
            val mmkv = MMKV.defaultMMKV()
            val legacyId = mmkv.decodeLong(LEGACY_KEY_DOWNLOAD_ID, -1L)
            if (legacyId != -1L) {
                (app.getSystemService(android.content.Context.DOWNLOAD_SERVICE)
                        as? android.app.DownloadManager)?.remove(legacyId)
                Timber.tag(TAG).i("已移除遗留的系统下载任务 %d", legacyId)
            }
            mmkv.removeValueForKey(LEGACY_KEY_DOWNLOAD_ID)
            mmkv.removeValueForKey(LEGACY_KEY_DOWNLOAD_TAG)
        }.onFailure { Timber.tag(TAG).w(it, "清理遗留系统下载任务失败") }
    }

    /**
     * 冷启动恢复。三种落点：
     *  - 已完成 → 直接是 [Phase.COMPLETED]，有挂起的 banner 就排队；
     *  - 有分片 → [Phase.PAUSED]，**不自动续传**（用户点了才跑）；
     *  - 什么都没 → 清记录。
     */
    private fun restore() {
        val rec = loadRecord()
        if (rec == null) {
            publish(null, Phase.IDLE)
            return
        }

        // 当前版本已经比记录里的新 → 这个包已经装上去了，清干净。
        // 只在「严格更新」时清：同版本留着（调试长按会下当前版本，重启就没了会很迷惑）。
        if (AppUpdateChecker.isNewerVersion(BuildConfig.VERSION_NAME, rec.versionName)) {
            deleteAllFiles()
            clearRecord()
            publish(null, Phase.IDLE)
            return
        }

        if (rec.status == STATUS_COMPLETED) {
            val apk = apkFile()
            if (apk != null && apk.exists() && apk.length() > 0 &&
                (rec.totalBytes <= 0L || apk.length() == rec.totalBytes)
            ) {
                publish(rec, Phase.COMPLETED, downloaded = apk.length())
                if (rec.bannerPending) banner.markPending()
            } else {
                deleteAllFiles()
                clearRecord()
                publish(null, Phase.IDLE)
            }
            return
        }

        if (!partsExist(rec)) {
            clearRecord()
            publish(null, Phase.IDLE)
            return
        }

        val downloaded = downloadedOf(rec)
        if (rec.totalBytes > 0L && downloaded >= rec.totalBytes) {
            // 上次在「分片都写完了、还没来得及拼装」的窗口被杀：补最后一步。
            rec.status = STATUS_RUNNING
            saveRecord(rec)
            publish(rec, Phase.RUNNING, downloaded)
            val token = ++runToken
            job = scope.launch {
                if (token == runToken) finish(rec, token)
            }
            return
        }

        rec.status = STATUS_PAUSED
        saveRecord(rec)
        publish(rec, Phase.PAUSED, downloaded, resumable = true)
    }

    // ---------------------------------------------------------------- 对外动作

    /**
     * 开始下载。同版本且有可用断点 → 续传；否则从头来。
     * 调用方（sheet）不需要自己判断状态，全部交给 [state] 渲染。
     */
    fun start(release: GitHubRelease) {
        val asset = AppUpdateChecker.findApkAsset(release) ?: return
        val existing = loadRecord()
        val current = _state.value.phase
        // 合并中也不接受「继续」：分片已经齐了，再点一次只会把拼装打断重来。
        if ((current == Phase.RUNNING || current == Phase.MERGING) && existing?.tag == release.tagName) return

        stopCurrent()

        val canResume = existing != null &&
            existing.tag == release.tagName &&
            existing.segments.isNotEmpty() &&
            downloadedOf(existing) > 0L &&
            partsExist(existing)

        if (canResume) {
            resumeFrom(existing!!, release)
        } else {
            beginFresh(release, asset)
        }
    }

    /** 无条件从头下：删掉断点、重新探测、重新分片。 */
    fun restart(release: GitHubRelease) {
        val asset = AppUpdateChecker.findApkAsset(release) ?: return
        stopCurrent()
        beginFresh(release, asset)
    }

    /** sheet 打开时登记：完成的那一刻如果还有订阅，就不发 banner。 */
    fun attachSheet() {
        sheetCount.incrementAndGet()
        // 用户已经看到结果了，欠着的那条 banner 不必再发（也顺手撤掉屏上那条）。
        consumeBannerPending()
        banner.clear()
    }

    fun detachSheet() {
        sheetCount.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** banner 系统刚就绪（冷启动时它晚于首个 onResume），补发欠着的那条提示。 */
    fun onBannerReady() {
        banner.onBannerReady()
    }

    /** 下载完成、但没有任何 sheet 在看时调用，交给 [UpdateBanner] 决定什么时候弹。 */
    fun consumeBannerPending() {
        val rec = loadRecord() ?: return
        if (!rec.bannerPending) return
        rec.bannerPending = false
        saveRecord(rec)
    }

    /** 供 banner 跳回 sheet 用：完成记录里存着整个 release。 */
    fun releaseForSheet(): GitHubRelease? = loadRecord()?.let { parseRelease(it) }

    fun apkFile(): File? = dir()?.let { File(it, APK_FILE_NAME) }

    fun hasApk(): Boolean = apkFile()?.let { it.exists() && it.length() > 0 } == true

    // ---------------------------------------------------------------- 内部实现

    private fun stopCurrent() {
        runToken++
        abortCalls()
        job?.cancel()
        job = null
    }

    private fun abortCalls() {
        calls.values.forEach { runCatching { it.cancel() } }
        calls.clear()
    }

    private fun beginFresh(release: GitHubRelease, asset: GitHubAsset) {
        deleteAllFiles()
        val rec = Record(
            tag = release.tagName,
            versionName = release.versionName,
            releaseJson = gson.toJson(release),
            url = GithubProxy.wrap(asset.downloadUrl),
            proxyPrefix = GithubProxy.currentPrefix(),
            // 先拿 API 给的 size 垫着，探测回来再校正（订阅合成的 release size 恒为 0）。
            totalBytes = asset.size.coerceAtLeast(0L),
            status = STATUS_RUNNING,
        )
        saveRecord(rec)
        publish(rec, Phase.RUNNING, downloaded = 0L)
        launchJob(rec, fresh = true)
    }

    private fun resumeFrom(rec: Record, release: GitHubRelease) {
        rec.releaseJson = gson.toJson(release)
        rec.versionName = release.versionName
        rec.status = STATUS_RUNNING
        saveRecord(rec)
        publish(
            rec,
            Phase.RUNNING,
            downloadedOf(rec),
            activeThreads = minOf(DEFAULT_THREADS, rec.segments.size),
        )
        launchJob(rec, fresh = false)
    }

    private fun launchJob(rec: Record, fresh: Boolean) {
        val token = ++runToken
        job = scope.launch {
            try {
                if (fresh) prepare(rec) else validateResume(rec)
                if (token != runToken) return@launch
                runJob(rec, token)
                if (token != runToken) return@launch
                finish(rec, token)
            } catch (e: CancellationException) {
                // 暂停 / 取消：状态已经由发起方写过，这里什么都不做。
            } catch (e: ProxyChangedException) {
                if (token != runToken) return@launch
                abortForProxyChange(rec)
            } catch (e: NotResumableException) {
                if (token != runToken) return@launch
                Timber.tag(TAG).w(e, "断点不可用")
                fail(rec, Reason.NOT_RESUMABLE, resumable = false)
            } catch (e: IOException) {
                if (token != runToken) return@launch
                Timber.tag(TAG).w(e, "下载中断")
                // 有分片且服务器支持 Range 才算「可继续」，否则只能重试。
                fail(rec, Reason.NETWORK, resumable = rec.rangeSupported && downloadedOf(rec) > 0L)
            } catch (e: Throwable) {
                if (token != runToken) return@launch
                Timber.tag(TAG).e(e, "下载失败")
                fail(rec, Reason.UNKNOWN, resumable = false)
            }
        }
    }

    /** 新下载：探测体积 / Range 支持 / 校验符，然后切片。 */
    private suspend fun prepare(rec: Record) {
        val probe = withContext(Dispatchers.IO) { probe(rec.url) }
        if (probe.totalBytes > 0L) rec.totalBytes = probe.totalBytes
        rec.etag = probe.etag
        rec.lastModified = probe.lastModified
        rec.rangeSupported = probe.rangeSupported
        rec.segments = split(rec.totalBytes, probe.rangeSupported)
        saveRecord(rec)
        publish(
            rec,
            Phase.RUNNING,
            downloaded = 0L,
            activeThreads = minOf(DEFAULT_THREADS, rec.segments.size),
        )
    }

    /**
     * 续传前的校验。任何一条不成立都抛 [NotResumableException]：分片里的字节无法证明
     * 还是同一个文件，硬拼出来就是「解析包出错」，比从头下更糟。
     */
    private suspend fun validateResume(rec: Record) {
        if (GithubProxy.currentPrefix() != rec.proxyPrefix) {
            throw NotResumableException("加速地址已变更")
        }
        if (!rec.rangeSupported) {
            throw NotResumableException("该源不支持断点续传")
        }
        val probe = withContext(Dispatchers.IO) { probe(rec.url) }
        if (!probe.rangeSupported) throw NotResumableException("服务器不再支持断点续传")
        if (probe.totalBytes > 0L && rec.totalBytes > 0L && probe.totalBytes != rec.totalBytes) {
            throw NotResumableException("文件大小已变化")
        }
        if (!probe.etag.isNullOrBlank() && !rec.etag.isNullOrBlank() && probe.etag != rec.etag) {
            throw NotResumableException("远端文件已更新")
        }
        if (probe.totalBytes > 0L) rec.totalBytes = probe.totalBytes
        if (!probe.etag.isNullOrBlank()) rec.etag = probe.etag
    }

    private class Probe(
        val totalBytes: Long,
        val rangeSupported: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    /**
     * 探测「总大小 / 是否支持 Range / ETag」。
     *
     * 先用 `bytes=0-0` 问一次。**不能只凭这一次的 200 就断定不支持断点续传**：
     * 个别 CDN 会把「只要 1 个字节」的请求特判成 200 全量返回，但正常区间照样给 206。
     * 所以 200 时再换一个正常大小的区间（`bytes=0-1048575`）复核一次，
     * 两次都 200 才认定这个源不能切。
     */
    private fun probe(url: String): Probe {
        val first = probeOnce(url, "bytes=0-0")
        if (first.rangeSupported) return first
        val second = probeOnce(url, "bytes=0-${MIN_CHUNK_BYTES - 1}")
        // 复核仍然 200：确认不支持 Range，沿用第一次拿到的总大小。
        if (!second.rangeSupported) {
            return Probe(
                totalBytes = if (second.totalBytes > 0L) second.totalBytes else first.totalBytes,
                rangeSupported = false,
                etag = second.etag ?: first.etag,
                lastModified = second.lastModified ?: first.lastModified,
            )
        }
        return second
    }

    private fun probeOnce(url: String, range: String): Probe {
        val request = Request.Builder()
            .url(url)
            .header("Range", range)
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { response ->
            val rangeSupported = response.code == 206
            val total = if (rangeSupported) {
                parseTotal(response.header("Content-Range"))
            } else {
                response.body?.contentLength()?.takeIf { it > 0L } ?: -1L
            }
            Timber.tag(TAG).i(
                "探测 Range=%s → HTTP %d, Accept-Ranges=%s, Content-Range=%s, total=%d, 最终URL=%s",
                range,
                response.code,
                response.header("Accept-Ranges"),
                response.header("Content-Range"),
                total,
                response.request.url,
            )
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return Probe(
                totalBytes = total,
                rangeSupported = rangeSupported,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }

    /** `Content-Range: bytes 0-0/12345` → 12345；`*` 或缺头 → -1。 */
    private fun parseTotal(header: String?): Long {
        val slash = header?.lastIndexOf('/') ?: return -1L
        if (slash <= 0 || slash >= header.length - 1) return -1L
        return header.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    /**
     * 切成 8 个等分大段。
     *
     * 每段都可以被空闲连接抢走一半——包括最后一段：没有证据表明尾段特殊，
     * 锁死它只会让「尾段慢」时没人能帮忙。
     */
    private fun split(total: Long, rangeSupported: Boolean): MutableList<Chunk> {
        // 不支持 Range 或长度未知：只能一条连接、整文件一段，且续传无从谈起。
        // 这种整文件片必须不可切——它没有 Range 语义，被切走一半会变成
        // 两条连接各自从 0 下整个文件。
        if (!rangeSupported || total <= 0L) {
            return CopyOnWriteArrayList(
                listOf(Chunk(0L, if (total > 0L) total - 1 else -1L, splittable = false))
            )
        }
        // 小于 1MB 不切：切了也是一条连接的事，白白多几次握手。
        if (total < MIN_CHUNK_BYTES) {
            return CopyOnWriteArrayList(listOf(Chunk(0L, total - 1, splittable = false)))
        }

        val count = minOf(DEFAULT_THREADS.toLong(), total / MIN_CHUNK_BYTES).toInt().coerceAtLeast(1)
        if (count <= 1) return CopyOnWriteArrayList(listOf(Chunk(0L, total - 1, splittable = false)))
        val base = total / count
        return CopyOnWriteArrayList((0 until count).map { i ->
            val start = base * i
            val end = if (i == count - 1) total - 1 else base * (i + 1) - 1
            Chunk(start, end)
        })
    }

    private suspend fun runJob(rec: Record, token: Int) = coroutineScope {
        val progress = ConcurrentHashMap<Int, AtomicLong>()
        rec.segments.indices.forEach { i ->
            progress[i] = AtomicLong(partFile(rec, i)?.length() ?: 0L)
        }
        /** 每个在飞分片「最后一次收到字节」的时刻，给 [stallWatchdog] 判静默用。 */
        val lastActivity = ConcurrentHashMap<Int, AtomicLong>()
        // 保护「分片表 + 认领指针」：抢片会往表尾追加，认领要保证每片只被拿一次。
        val lock = Any()
        var nextClaim = 0
        val active = AtomicInteger(0)

        fun progressOf(i: Int): Long = progress[i]?.get() ?: 0L

        fun total(): Long = rec.segments.indices.sumOf { progressOf(it) }

        fun isComplete(i: Int): Boolean {
            val chunk = rec.segments[i]
            return chunk.bounded && progressOf(i) >= chunk.size
        }

        /**
         * 领一片活：先拿没被认领过的，没有就抢。
         *
         * 抢的规则：在所有「可切且没下完」的段里挑剩余最多的，把它的剩余部分切成
         * **抢的人拿 2/3、原主留 1/3**。给抢的人多分一点，是因为连接刚建立时往往慢、
         * 之后才提速——分得太碎的话，快起来的那条很快就撞到分片 end 刹停，又得再抢一次；
         * 每次抢都多一次握手和 Range 往返。让一次抢到的够它跑一阵，总请求数才少。
         *
         * 剩余不足 3MB 就不切：保证切完两边都还 ≥1MB，不产生碎块。
         */
        fun claimOrSteal(): Int? = synchronized(lock) {
            while (nextClaim < rec.segments.size) {
                val index = nextClaim++
                if (!isComplete(index)) return index
            }
            var best = -1
            var bestRemaining = 0L
            rec.segments.forEachIndexed { i, chunk ->
                if (!chunk.splittable || isComplete(i)) return@forEachIndexed
                val remaining = chunk.end - (chunk.start + progressOf(i)) + 1
                if (remaining > bestRemaining) {
                    bestRemaining = remaining
                    best = i
                }
            }
            if (best < 0 || bestRemaining < MIN_CHUNK_BYTES * 3) return null

            val chunk = rec.segments[best]
            val done = progressOf(best)
            val originalEnd = chunk.end
            val originalSize = chunk.size
            // 抢的人拿后 2/3，原主留下前 1/3。
            val take = bestRemaining * 2 / 3
            val ownerEnd = originalEnd - take
            chunk.end = ownerEnd
            // 父段已经下过一半：切出来的子段读超时减半；子段再被切时继续减半，
            // 于是越往后越「没耐心」，一路指数递减到下限。
            // 注意用**切之前**的长度算比例：chunk.size 这时已经被改小了，
            // 拿它算会把 43% 也当成过半。
            val half = originalSize > 0L && done * 2L >= originalSize
            val childTimeout = if (half) {
                maxOf(MIN_READ_TIMEOUT_MS, chunk.readTimeoutMs / 2)
            } else {
                chunk.readTimeoutMs
            }
            val tail = Chunk(
                start = ownerEnd + 1,
                end = originalEnd,
                splittable = true,
                stolen = true,
                readTimeoutMs = childTimeout,
            )
            rec.segments.add(tail)
            val newIndex = rec.segments.size - 1
            progress[newIndex] = AtomicLong(0L)
            nextClaim = rec.segments.size
            // 记下新的切法：进程被杀后要按这个表续传，不能还按切之前的。
            saveRecord(rec)
            Timber.tag(TAG).i(
                "抢片：分片 %d 剩 %d 字节（已下 %d%%），切走 %d 字节给空闲连接，读超时 %dms",
                best,
                bestRemaining,
                if (originalSize > 0L) done * 100 / originalSize else 0L,
                take,
                childTimeout,
            )
            return newIndex
        }

        val ticker = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (token == runToken) {
                    publish(rec, Phase.RUNNING, total(), activeThreads = active.get())
                }
            }
        }
        // 收尾阶段的静默看门狗：见 [STALL_ALLOW_80]。
        val watchdog = launch {
            while (isActive) {
                delay(STALL_WATCH_INTERVAL_MS)
                if (token != runToken) return@launch
                val allow = stallAllowance(total(), rec.totalBytes)
                if (allow == Long.MAX_VALUE) continue
                val now = SystemClock.elapsedRealtime()
                for ((index, call) in calls) {
                    val last = lastActivity[index]?.get() ?: continue
                    val silent = now - last
                    if (silent > allow) {
                        Timber.tag(TAG).w(
                            "分片 %d 已静默 %dms（整体 %d%%，上限 %dms），掐掉重连",
                            index,
                            silent,
                            if (rec.totalBytes > 0L) total() * 100 / rec.totalBytes else 0L,
                            allow,
                        )
                        runCatching { call.cancel() }
                    }
                }
            }
        }
        try {
            (0 until minOf(DEFAULT_THREADS, rec.segments.size)).map {
                async(Dispatchers.IO) {
                    while (true) {
                        val index = claimOrSteal() ?: return@async
                        active.incrementAndGet()
                        try {
                            downloadSegmentWithRetry(rec, index, progress, lastActivity)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        } catch (t: Throwable) {
            // 一个分片挂了就立刻掐掉其余连接。它们阻塞在 `Call.execute()` 上，
            // 不响应协程取消；只靠 coroutineScope 的传播会一直等它们把整段下完
            //（换源 / 磁盘满这类场景下就是几十秒的假死）。
            abortCalls()
            throw t
        } finally {
            ticker.cancel()
            watchdog.cancel()
        }
        if (token == runToken) {
            publish(rec, Phase.RUNNING, total(), activeThreads = 0)
        }
    }

    /** 整体进度对应的「静默容忍上限」；没到 80% 就不管，交给 OkHttp 自己的读超时。 */
    private fun stallAllowance(done: Long, total: Long): Long {
        if (total <= 0L) return Long.MAX_VALUE
        return when {
            done * 100L >= total * 95L -> STALL_ALLOW_95
            done * 100L >= total * 90L -> STALL_ALLOW_90
            done * 100L >= total * 80L -> STALL_ALLOW_80
            else -> Long.MAX_VALUE
        }
    }

    /**
     * 单分片带重试。断点类错误（不支持 Range / 换源）**不重试**——重连也是同样的结果；
     * 网络类错误从当前偏移接着要 Range 即可，分片文件本身就是断点。
     */
    private suspend fun downloadSegmentWithRetry(
        rec: Record,
        index: Int,
        progress: ConcurrentHashMap<Int, AtomicLong>,
        lastActivity: ConcurrentHashMap<Int, AtomicLong>,
    ) {
        var attempt = 0
        while (true) {
            try {
                downloadSegment(rec, index, progress, lastActivity)
                return
            } catch (e: NotResumableException) {
                throw e
            } catch (e: ProxyChangedException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_SEGMENT_ATTEMPTS || !currentCoroutineContext().isActive) throw e
                // 用「界面上那个百分比」判断，而不是这一片自己的进度：
                // 用户盯的是整单 99%，这时候哪一片断流都该立刻重连；
                // 只看本片进度的话，一片 20% 的大段断流仍会白等 600ms。
                val done = progress.values.sumOf { it.get() }
                val nearlyDone = rec.totalBytes > 0L && done * 10L >= rec.totalBytes * 9L
                if (nearlyDone) {
                    Timber.tag(TAG).w(
                        e,
                        "整体已下 %d%%，分片 %d 断流立即重连",
                        done * 100 / rec.totalBytes,
                        index,
                    )
                } else {
                    Timber.tag(TAG).w(e, "分片 %d 第 %d 次中断，%.0fms 后重试", index, attempt, 600f * attempt)
                    delay(600L * attempt)
                }
            }
        }
    }

    private fun downloadSegment(
        rec: Record,
        index: Int,
        progress: ConcurrentHashMap<Int, AtomicLong>,
        lastActivity: ConcurrentHashMap<Int, AtomicLong>,
    ) {
        val chunk = rec.segments[index]
        val file = partFile(rec, index) ?: throw IOException("下载目录不可用")
        // 连上之前也算静默：连接迟迟建不起来，收尾阶段同样是在拖整单。
        lastActivity[index] = AtomicLong(SystemClock.elapsedRealtime())
        var have = if (file.exists()) file.length() else 0L
        // 这一片可能被抢过：边界变短了。多出来的尾巴截掉，前面已下好的仍然有效
        //（直接删掉重下会把已经拿到的几 MB 白白扔掉）。
        if (chunk.bounded && have > chunk.size) {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(chunk.size) }
            have = chunk.size
        }
        if (chunk.bounded && have >= chunk.size) {
            progress[index]?.set(have)
            return
        }

        val builder = Request.Builder().url(rec.url)
        if (rec.rangeSupported) {
            builder.header("Range", "bytes=${chunk.start + have}-${chunk.end}")
        } else {
            // 整文件单连接：明确关掉 gzip，否则拿到的是解压后的流，长度对不上。
            builder.header("Accept-Encoding", "identity")
        }

        val call = clientFor(chunk.readTimeoutMs).newCall(builder.build())
        calls[index] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                if (rec.rangeSupported && response.code != 206) {
                    throw NotResumableException("服务器忽略了 Range（HTTP ${response.code}）")
                }
                val body = response.body ?: throw IOException("响应为空")
                var written = 0L
                FileOutputStream(file, have > 0L).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            // 边界每轮都重读：这一片可能刚被空闲连接切走后半截，
                            // 拿到新 end 就立刻收手，别把对方那份也写了。
                            val limit = if (chunk.bounded) chunk.size else -1L
                            if (limit >= 0 && have + written >= limit) break
                            // 换源检测放在读循环里：每个 buffer 过一下 CAS 节流，
                            // 真正比对前缀最多每秒一次，发现不一致立刻抛。
                            checkProxy(rec)
                            val read = input.read(buffer)
                            if (read == -1) break
                            val allowed = if (limit >= 0) {
                                minOf(read.toLong(), limit - have - written).toInt()
                            } else {
                                read
                            }
                            if (allowed <= 0) break
                            out.write(buffer, 0, allowed)
                            written += allowed
                            progress[index]?.set(have + written)
                            lastActivity[index]?.set(SystemClock.elapsedRealtime())
                        }
                    }
                    out.flush()
                }
                // 被切走时可能已经多写了几个 buffer，截到新边界，别让拼装多出一截。
                val expected = if (chunk.bounded) chunk.size else have + written
                if (chunk.bounded && file.length() > expected) {
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(expected) }
                }
                val actual = file.length()
                if (actual < expected) {
                    throw IOException("分片 $index 长度不足（$actual < $expected）")
                }
                progress[index]?.set(expected)
            }
        } finally {
            calls.remove(index)
            lastActivity.remove(index)
        }
    }

    private fun checkProxy(rec: Record) {
        val now = SystemClock.elapsedRealtime()
        val last = proxyCheckedAt.get()
        if (now - last < PROXY_CHECK_INTERVAL_MS) return
        if (!proxyCheckedAt.compareAndSet(last, now)) return
        if (GithubProxy.currentPrefix() != rec.proxyPrefix) throw ProxyChangedException()
    }

    /** 所有分片到齐：拼成单个 APK、校验长度、清分片。 */
    private fun finish(rec: Record, token: Int) {
        if (token != runToken) return
        // 先切到「合并中」再动手：拼装是纯磁盘操作，这期间没有进度可报，
        // 不给状态的话界面就停在 99% 上，看着像卡死。
        publish(rec, Phase.MERGING, downloaded = rec.totalBytes)
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val apk = assemble(rec) ?: throw IOException("拼装失败")
            Timber.tag(TAG).i("拼装完成：%d 字节，耗时 %dms", apk.length(), SystemClock.elapsedRealtime() - startedAt)
            rec.status = STATUS_COMPLETED
            rec.bannerPending = !sheetAttached
            saveRecord(rec)
            publish(rec, Phase.COMPLETED, downloaded = apk.length())
            if (rec.bannerPending) banner.markPending()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "拼装 APK 失败")
            fail(rec, Reason.IO, resumable = false)
        }
    }

    private fun assemble(rec: Record): File? {
        val out = apkFile() ?: return null
        if (out.exists()) out.delete()
        // 抢片会把新片追加在表尾，表序不等于偏移序，必须按 start 排序拼。
        val order = rec.segments.indices.sortedBy { rec.segments[it].start }
        // 1MB 一块：64KB 时 45MB 要 700 多次 write，每次都过一层外置存储的 FUSE，
        // 真机上能差出好几百毫秒。
        FileOutputStream(out).use { sink ->
            order.forEach { i ->
                val part = partFile(rec, i) ?: return@forEach
                if (!part.exists()) throw IOException("分片 $i 丢失")
                FileInputStream(part).use { input -> input.copyTo(sink, ASSEMBLE_BUFFER_SIZE) }
            }
        }
        val length = out.length()
        if (rec.totalBytes > 0L && length != rec.totalBytes) {
            out.delete()
            throw IOException("拼装后长度不符：$length != ${rec.totalBytes}")
        }
        deleteParts(rec)
        return out
    }

    private fun fail(rec: Record, reason: Reason, resumable: Boolean) {
        abortCalls()
        val downloaded = downloadedOf(rec)
        rec.status = STATUS_FAILED
        saveRecord(rec)
        if (!resumable) deleteParts(rec)
        publish(
            rec,
            Phase.FAILED,
            downloaded = if (resumable) downloaded else 0L,
            resumable = resumable,
            reason = reason,
        )
    }

    /** 加速地址变了：无条件取消，断点一并作废（字节无法证明与新源同源）。 */
    private fun abortForProxyChange(rec: Record) {
        abortCalls()
        deleteParts(rec)
        rec.status = STATUS_FAILED
        saveRecord(rec)
        publish(rec, Phase.FAILED, downloaded = 0L, resumable = false, reason = Reason.PROXY_CHANGED)
    }

    // ---------------------------------------------------------------- 状态与文件

    private fun publish(
        rec: Record?,
        phase: Phase,
        downloaded: Long = 0L,
        resumable: Boolean = false,
        reason: Reason? = null,
        activeThreads: Int = 0,
    ) {
        _state.value = State(
            phase = phase,
            tag = rec?.tag,
            versionName = rec?.versionName,
            totalBytes = rec?.totalBytes ?: 0L,
            downloadedBytes = downloaded,
            resumable = resumable,
            reason = reason,
            threads = rec?.segments?.size?.let { minOf(DEFAULT_THREADS, it) } ?: 0,
            activeThreads = activeThreads,
        )
    }

    private fun downloadedOf(rec: Record): Long {
        if (rec.segments.isEmpty()) return 0L
        var total = 0L
        rec.segments.indices.forEach { i ->
            total += partFile(rec, i)?.let { if (it.exists()) it.length() else 0L } ?: 0L
        }
        return total
    }

    private fun partsExist(rec: Record): Boolean {
        if (rec.segments.isEmpty()) return false
        return rec.segments.indices.any { i -> partFile(rec, i)?.exists() == true }
    }

    private fun partFile(rec: Record, index: Int): File? =
        dir()?.let { File(it, "$APK_FILE_NAME$PART_FILE_SUFFIX$index") }

    private fun deleteParts(rec: Record) {
        rec.segments.indices.forEach { i -> partFile(rec, i)?.delete() }
        // 分片数量可能变过（上次 8 片这次 4 片），按名字扫一遍兜底。
        dir()?.listFiles()?.forEach { file ->
            if (file.name.startsWith("$APK_FILE_NAME$PART_FILE_SUFFIX")) file.delete()
        }
    }

    private fun deleteAllFiles() {
        dir()?.listFiles()?.forEach { file ->
            if (file.name.startsWith(APK_FILE_NAME)) file.delete()
        }
    }

    private fun dir(): File? =
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir

    private fun loadRecord(): Record? {
        val raw = runCatching { MMKV.defaultMMKV().decodeString(KEY_RECORD, "") }.getOrNull()
        if (raw.isNullOrBlank()) return null
        val rec = runCatching { gson.fromJson(raw, Record::class.java) }.getOrNull() ?: return null
        // Gson 反序列化出来的是 ArrayList；换回写时复制表，抢片时才敢边追加边读。
        rec.segments = CopyOnWriteArrayList(rec.segments)
        return rec
    }

    private fun saveRecord(rec: Record) {
        runCatching { MMKV.defaultMMKV().encode(KEY_RECORD, gson.toJson(rec)) }
            .onFailure { Timber.tag(TAG).w(it, "保存下载记录失败") }
    }

    private fun clearRecord() {
        runCatching { MMKV.defaultMMKV().removeValueForKey(KEY_RECORD) }
        cachedReleaseJson = null
        cachedRelease = null
    }

    private fun parseRelease(rec: Record): GitHubRelease? {
        if (rec.releaseJson == cachedReleaseJson) return cachedRelease
        val parsed = runCatching { gson.fromJson(rec.releaseJson, GitHubRelease::class.java) }.getOrNull()
        cachedReleaseJson = rec.releaseJson
        cachedRelease = parsed
        return parsed
    }
}
