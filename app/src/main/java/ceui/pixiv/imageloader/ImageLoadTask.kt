package ceui.pixiv.imageloader

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * V3 图片加载系统里「一张图 = 一个任务」的最小单元。
 *
 * 同一个 url 在 [ImageTaskRegistry] 里只存在一个 ImageLoadTask 实例。于是详情页 B 和大图页 C
 * 只要请求同一 url(同分辨率),拿到的就是**同一个任务**:同一条 [state] 进度、同一份下载结果,不各下一次。
 * 任务由进程级 registry 持有,不随 fragment/activity 销毁,所以 B 返回列表再进 B/C,进度和结果都还在。
 *
 * 设计上刻意区别于 ui/task:
 * - **单一职责 + 依赖接缝**:只做「状态编排」,抓字节交给 [ImageFetcher],不认识 Glide/ProgressTracker(可替换、可测)。
 * - **状态单一数据源**:进度只推进 [ImageLoadState.Loading],终态只由真正拿到文件决定,杜绝
 *   ui/task 那种「进度先到 100%、文件还没就绪」的竞态。
 * - **状态无锁并发**:进度回调在 IO 线程、终态在 registry 线程,用 [MutableStateFlow.update] 原子 CAS,
 *   不再像 ui/task 那样 `LiveData.value` / `postValue` 混用踩线程。
 * - **重试是任务自己的能力**([retry]),而不是像 ui/task 那样让缓存池/控制器 remove 后重建。
 *   [start] 对已失败(Error)的任务**不自动重来**,避免列表复用时对 404 之类持久失败反复重下(retry 风暴)。
 */
class ImageLoadTask(
    val request: ImageRequest,
    private val scope: CoroutineScope,
    private val fetcher: ImageFetcher = GlideImageFetcher,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val shortUrl = request.url.substringAfterLast('/')

    private val _state = MutableStateFlow<ImageLoadState>(ImageLoadState.Idle)
    val state: StateFlow<ImageLoadState> = _state.asStateFlow()

    /**
     * Java 互操作用的 LiveData 视图(IllustAdapter 等 Java 侧 `observe`,Java 没法 collect StateFlow)。
     * 核心仍是 [state] 这条 StateFlow;本 LiveData 只是边界适配,懒建、多观察者共享同一份。
     */
    val stateLiveData: LiveData<ImageLoadState> by lazy { state.asLiveData() }

    /** 当前已下好的文件(仅成功后非空),供占位链/缓存命中/一键保存快速取用。 */
    val currentFile: File? get() = _state.value.fileOrNull?.takeIf { it.isUsableImageFile() }

    private var job: Job? = null

    /**
     * 幂等启动:正在下载/已成功/已失败都不重复触发。B、C 各自 start() 时,只有第一个真正拉起下载,
     * 其余附着到同一条 [state]。失败任务只能靠 [retry] 显式重来。
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        when (val current = _state.value) {
            // 磁盘缓存文件还在就直接复用;被清了(exists=false)才落到下面重抓。
            is ImageLoadState.Success -> {
                if (current.file.isUsableImageFile()) return
                // Glide 磁盘缓存可能被系统/用户清掉。必须先离开旧 Success,否则 awaitFile()
                // 会立刻读到 stale terminal state,把不存在的 File 交给保存/AI 流程。
                _state.value = ImageLoadState.Loading(0)
            }
            // 终态失败不自动重来,交给 retry(),避免复用触发 retry 风暴。
            is ImageLoadState.Error -> return
            else -> Unit
        }
        Timber.d("[ImgV3] task start url=$shortUrl")
        job = scope.launch { runDownload() }
    }

    /** 失败/需要重来时重置状态并重新下载。 */
    @Synchronized
    fun retry() {
        Timber.d("[ImgV3] task retry url=$shortUrl")
        job?.cancel()
        job = null
        _state.value = ImageLoadState.Idle
        start()
    }

    /**
     * 挂起直到成功拿到文件;已成功则立即返回,失败则抛出原因。
     * 供「保存到相册」等下游用例复用**同一个**共享任务的结果,不重复下载。
     */
    suspend fun awaitFile(onProgress: (Int) -> Unit = {}): File {
        // 上一次失败就重来一次(对齐旧 TaskPool「取到 errored 任务即换新重下」的行为);否则幂等启动。
        // 这是显式「我要这个文件」的取用点(保存/AI),不是列表滚动,重试一次不会造成风暴。
        if (_state.value is ImageLoadState.Error) retry() else start()
        return when (val terminal = state.onEach {
            if (it is ImageLoadState.Loading) onProgress(it.percent)
        }.first { it.isTerminal }) {
            is ImageLoadState.Success -> {
                // Cache eviction can race Success delivery. It will not produce another state,
                // so rejecting Success in first's predicate would leave a save waiting forever.
                if (!terminal.file.isUsableImageFile()) {
                    throw IOException("image cache disappeared before use: ${terminal.file.path}")
                }
                terminal.file
            }
            is ImageLoadState.Error -> throw terminal.cause
            else -> error("unreachable non-terminal state: $terminal")
        }
    }

    private suspend fun runDownload() {
        val startMs = elapsedRealtime()
        _state.value = ImageLoadState.Loading(0)
        try {
            val file = fetcher.fetch(request.url) { percent ->
                // 只在下载中推进百分比,原子 CAS 避免覆盖已到来的终态。
                _state.update { if (it is ImageLoadState.Loading) ImageLoadState.Loading(percent) else it }
            }
            // Success 的契约是「文件可用」:空文件(镜像站回 200 空 body 之类)若也进 Success,
            // awaitFile() 的可用性谓词永远等不到终态、保存/AI/壁纸流程会无声挂死。这里改走 Error,可 retry。
            if (!file.isUsableImageFile()) {
                throw IOException("fetched file is missing or empty: ${file.path}")
            }
            _state.value = ImageLoadState.Success(file)
            Timber.d("[ImgV3] task SUCCESS url=$shortUrl totalMs=${elapsedRealtime() - startMs} size=${file.length()}")
        } catch (ce: CancellationException) {
            Timber.d("[ImgV3] task CANCELLED url=$shortUrl")
            throw ce
        } catch (ex: Exception) {
            _state.value = ImageLoadState.Error(ex)
            Timber.e(ex, "[ImgV3] task ERROR url=$shortUrl totalMs=${elapsedRealtime() - startMs}")
        }
    }

    private fun File.isUsableImageFile(): Boolean = exists() && length() > 0
}
