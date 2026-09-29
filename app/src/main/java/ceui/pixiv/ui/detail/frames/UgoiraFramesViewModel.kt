package ceui.pixiv.ui.detail.frames

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import ceui.pixiv.ui.bulk.UgoiraEngine
import ceui.pixiv.ui.bulk.UgoiraFrames
import ceui.pixiv.ui.bulk.UgoiraProgress
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 逐帧页的加载状态。 */
internal sealed interface FramesLoad {
    data class Loading(val progress: UgoiraProgress?) : FramesLoad
    data class Ready(val frames: UgoiraFrames) : FramesLoad
    data object Failed : FramesLoad
}

/** 舞台上要画的那一帧。 */
internal data class FrameImage(val index: Int, val bitmap: Bitmap)

/**
 * 逐帧页的数据面：拿原帧、解码显示帧与时间轴缩略图、记当前帧 / 标记 / 播放速度、保存与分享。
 *
 * 帧就是 pixiv 解压出来的 JPEG（[UgoiraEngine.loadOriginalFrames]），保存时原样拷出，
 * 不经过 mp4 或 GIF 的有损再编码 —— 这是本页相对「截屏视频」最大的价值。
 *
 * 当前帧、标记与速度放在 [SavedStateHandle]：旋转、分屏、进程回收回来还停在原处。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class UgoiraFramesViewModel(
    app: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(app) {

    private val _load = MutableStateFlow<FramesLoad>(FramesLoad.Loading(null))
    val load: StateFlow<FramesLoad> = _load.asStateFlow()

    val index: StateFlow<Int> = state.getStateFlow(KEY_INDEX, 0)
    private val _marks = MutableStateFlow(state.get<IntArray>(KEY_MARKS)?.toSet().orEmpty())
    val marks: StateFlow<Set<Int>> = _marks.asStateFlow()
    val speedIndex: StateFlow<Int> = state.getStateFlow(KEY_SPEED, DEFAULT_SPEED)

    private val _frame = MutableStateFlow<FrameImage?>(null)
    val frame: StateFlow<FrameImage?> = _frame.asStateFlow()

    /** 时间轴缩略图每解出一批就 +1，页面据此重画胶片。 */
    private val _thumbTick = MutableStateFlow(0)
    val thumbTick: StateFlow<Int> = _thumbTick.asStateFlow()
    private var thumbs: Array<Bitmap?> = emptyArray()

    private var loadJob: Job? = null
    private var thumbJob: Job? = null
    private var decodeJob: Job? = null
    private var prefetchJob: Job? = null

    // 显示帧：单线程解码，只追最新请求；缓存按字节计，上限 1/8 堆或 64MB。
    private val decoder = Dispatchers.IO.limitedParallelism(1)
    private val thumbDecoder = Dispatchers.IO.limitedParallelism(1)
    private val cache = object : LruCache<Int, Bitmap>(
        minOf(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    private var illust: Illust? = null

    val speed: Float get() = SPEEDS[speedIndex.value.coerceIn(0, SPEEDS.lastIndex)]

    fun start(target: Illust) {
        illust = target
        if (_load.value is FramesLoad.Ready || loadJob?.isActive == true) return
        _load.value = FramesLoad.Loading(null)
        loadJob = viewModelScope.launch {
            val progressJob = launch {
                UgoiraEngine.progressOf(target.id).collect { _load.value = FramesLoad.Loading(it) }
            }
            try {
                val frames = UgoiraEngine.loadOriginalFrames(target)
                progressJob.cancel()
                if (frames.files.isEmpty()) error("no frames")
                state[KEY_INDEX] = index.value.coerceIn(0, frames.files.size - 1)
                _marks.value = _marks.value.filterTo(sortedSetOf()) { it in frames.files.indices }
                thumbs = arrayOfNulls(frames.files.size)
                _load.value = FramesLoad.Ready(frames)
                decodeThumbs(frames)
                show(index.value)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                progressJob.cancel()
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[frames] illust=%d 原帧加载失败", target.id)
                _load.value = FramesLoad.Failed
            }
        }
    }

    fun retry() {
        val target = illust ?: return
        UgoiraEngine.invalidate(target.id)
        start(target)
    }

    private val ready: UgoiraFrames? get() = (_load.value as? FramesLoad.Ready)?.frames

    // ── 当前帧 ──────────────────────────────────────────────────────

    /** 换到第 [i] 帧：缓存命中立刻上屏，否则解码后上屏；顺手预取后面两帧。 */
    fun show(i: Int, prefetchForward: Boolean = true) {
        val frames = ready ?: return
        val target = i.coerceIn(0, frames.files.size - 1)
        if (index.value != target) state[KEY_INDEX] = target
        val hit = cache.get(target)
        if (hit != null) {
            _frame.value = FrameImage(target, hit)
        } else {
            decodeJob?.cancel()
            decodeJob = viewModelScope.launch(decoder) {
                val bmp = decodeFull(frames, target) ?: return@launch
                withContext(Dispatchers.Main) {
                    // 解码期间用户可能已经拨到别处：只让最新的那一帧上屏。
                    if (index.value == target) _frame.value = FrameImage(target, bmp)
                }
            }
        }
        // 预取只保留最新一份：快速拖动时每跨一帧都会来一次，不取消的话过期预取会在单线程
        // 解码器上排成长队，用户停手的那一帧反而要等它们全解完才上屏。
        val step = if (prefetchForward) 1 else -1
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(decoder) {
            for (k in 1..2) {
                if (!isActive) return@launch
                val j = Math.floorMod(target + k * step, frames.files.size)
                if (cache.get(j) == null) decodeFull(frames, j)
            }
        }
    }

    private fun decodeFull(frames: UgoiraFrames, i: Int): Bitmap? {
        cache.get(i)?.let { return it }
        val file = frames.files[i]
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bmp == null) {
            // 帧文件被系统清缓存删掉了：转失败态，重试会走完整 pipeline 重建。
            if (!file.isFile) {
                Timber.tag(UGOIRA_LOG_TAG).w("[frames] 帧文件已不在 %s", file.name)
                _load.value = FramesLoad.Failed
            }
            return null
        }
        cache.put(i, bmp)
        return bmp
    }

    fun thumbAt(i: Int): Bitmap? = thumbs.getOrNull(i)

    /** 时间轴缩略图：短边约 [THUMB_PX]、RGB_565，从当前帧向两侧铺开解码，先看见的先出。 */
    private fun decodeThumbs(frames: UgoiraFrames) {
        thumbJob?.cancel()
        val arr = thumbs
        thumbJob = viewModelScope.launch(thumbDecoder) {
            val n = frames.files.size
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(frames.files[0].absolutePath, bounds)
            var sample = 1
            val shortSide = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (shortSide / (sample * 2) >= THUMB_PX) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val center = index.value
            val order = buildList {
                add(center)
                for (d in 1 until n) {
                    if (center + d < n) add(center + d)
                    if (center - d >= 0) add(center - d)
                }
            }
            order.forEachIndexed { k, i ->
                if (!isActive) return@launch
                arr[i] = runCatching { BitmapFactory.decodeFile(frames.files[i].absolutePath, opts) }.getOrNull()
                if (k % 6 == 5 || k == order.lastIndex) _thumbTick.value += 1
            }
        }
    }

    // ── 标记与速度 ─────────────────────────────────────────────────

    fun toggleMark(i: Int) = setMarks(if (i in _marks.value) _marks.value - i else _marks.value + i)

    fun setMarks(value: Set<Int>) {
        _marks.value = value.toSortedSet()
        state[KEY_MARKS] = value.toIntArray()
    }

    fun cycleSpeed() {
        state[KEY_SPEED] = (speedIndex.value + 1) % SPEEDS.size
    }

    // ── 保存与分享 ─────────────────────────────────────────────────

    /** 原帧 JPEG 原样写进用户相册，命名沿用作品模板 + `_frameNN` 后缀；返回写入张数。 */
    suspend fun save(indices: List<Int>): Int {
        val frames = ready ?: error("frames not ready")
        val target = illust ?: error("illust not bound")
        return withContext(Dispatchers.IO) {
            indices.count { i ->
                // open 返回 null = 用户选了「跳过已存在」且同名文件在：算已保存。
                val handle = DownloadsRegistry.downloads.openDerived(
                    DownloadItems.ugoiraFrame(target),
                    frameSuffix(i, frames.files.size),
                ) ?: return@count true
                try {
                    handle.stream.use { out -> frames.files[i].inputStream().use { it.copyTo(out) } }
                    handle.onFinish()
                    true
                } catch (t: Throwable) {
                    handle.onAbort()
                    throw t
                }
            }
        }
    }

    /** 分享用副本：拷进 FileProvider 暴露的缓存目录，文件名带帧序号。 */
    suspend fun shareUris(indices: List<Int>): ArrayList<Uri> {
        val frames = ready ?: error("frames not ready")
        val target = illust ?: error("illust not bound")
        val ctx = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            val dir = File(ctx.externalCacheDir ?: ctx.cacheDir, "images").apply { mkdirs() }
            indices.mapTo(ArrayList()) { i ->
                val out = File(dir, "${target.id}${frameSuffix(i, frames.files.size)}.jpg")
                frames.files[i].copyTo(out, overwrite = true)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", out)
            }
        }
    }

    override fun onCleared() {
        cache.evictAll()
        thumbs = emptyArray()
    }

    companion object {
        /** 0.25× 看清过渡帧，2× 快速找位置。 */
        val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f)
        private const val DEFAULT_SPEED = 2 // SPEEDS[2] = 1×
        private const val THUMB_PX = 120
        private const val KEY_INDEX = "frame_index"
        private const val KEY_MARKS = "frame_marks"
        private const val KEY_SPEED = "frame_speed"

        /** `_frame07`：序号从 1 起，按总帧数补零，文件管理器里按名排序即按时间。 */
        fun frameSuffix(i: Int, count: Int): String {
            val width = maxOf(2, count.toString().length)
            return "_frame" + (i + 1).toString().padStart(width, '0')
        }
    }
}
