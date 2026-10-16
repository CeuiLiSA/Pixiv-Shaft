package ceui.pixiv.ui.filter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.imageloader.PageImageSource
import ceui.pixiv.imageloader.PageImageSourceResolver
import ceui.pixiv.imageloader.awaitFile
import ceui.pixiv.ui.upscale.decodeBoundedArgb
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed interface FilterLoad {
    data class Loading(val percent: Int?) : FilterLoad
    data object Failed : FilterLoad
    data class Ready(val preview: Bitmap) : FilterLoad
}

enum class FilterExport { SAVE, SHARE }

sealed interface FilterExportResult {
    data object Saved : FilterExportResult
    data class Share(val uri: Uri, val mime: String) : FilterExportResult
    data class Failed(val message: String) : FilterExportResult
}

/**
 * 滤镜页的状态与重活。
 *
 * - **预览**：原图按舞台尺寸解一张小图，所有滤镜都在它上面由 native 渲染；渲染跑在单线程调度器上、
 *   `collectLatest` 合并，拖滑条时只渲最新的那一组参数，而且先用 1/4 像素的草稿跟手、停手后补清晰帧。输出在三只缓冲之间轮换，舞台正挂着的
 *   那一只（[onDisplayed]）绝不会被下一次渲染写到，不会画出半帧。
 * - **滤镜条**：从预览小图居中裁一张缩略图，每只预设各渲一次（几万像素，毫秒级），按滤镜条顺序逐个出。
 * - **导出**：全分辨率解码（可变位图）→ native 原地渲染 → 编码。结果按参数缓存，
 *   先保存再分享（或反过来）不重算。跑在 viewModelScope 上，旋转不打断。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageFilterViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val _load = MutableStateFlow<FilterLoad>(FilterLoad.Loading(null))
    val load: StateFlow<FilterLoad> = _load.asStateFlow()

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<FilterState> = _state.asStateFlow()

    /** 当前参数渲染出的预览帧；参数等同原图时就是预览小图本身。 */
    private val _rendered = MutableStateFlow<Bitmap?>(null)
    val rendered: StateFlow<Bitmap?> = _rendered.asStateFlow()

    /** 按 [FilterPreset.ordinal] 排的滤镜条缩略图，渲好一只填一只。 */
    private val _thumbs = MutableStateFlow<List<Bitmap?>>(List(FilterPreset.entries.size) { null })
    val thumbs: StateFlow<List<Bitmap?>> = _thumbs.asStateFlow()

    private val _exporting = MutableStateFlow<FilterExport?>(null)
    val exporting: StateFlow<FilterExport?> = _exporting.asStateFlow()

    private val _results = Channel<FilterExportResult>(Channel.BUFFERED)
    val results = _results.receiveAsFlow()

    /** 「滤镜 / 调节」两个模式，和调节模式下选中的那一项。只是界面状态，存 SavedState 让旋转原位恢复。 */
    val adjustMode: StateFlow<Boolean> = saved.getStateFlow(KEY_MODE, false)
    val adjustTool: StateFlow<Int> = saved.getStateFlow(KEY_TOOL, 0)

    private lateinit var illust: Illust
    private var page = 0
    private var source: File? = null
    private var loadJob: Job? = null

    /** 预览与缩略图的渲染全部串行在这一条线上：native 自己会铺满所有核心，这里不必再并行。 */
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var buffers: Array<Bitmap>? = null

    /** 拖动中用的草稿：预览小图再缩一半（像素 1/4），手停下来再渲清晰的那一帧。 */
    private var draft: Bitmap? = null
    private var draftBuffers: Array<Bitmap>? = null
    private var lastChangeAt = 0L

    /** 舞台此刻挂着的那一帧（主线程写、渲染线程读）：渲染只挑既没挂上、也不是刚发布的缓冲来写。 */
    @Volatile private var displayed: Bitmap? = null

    private var lastExport: Pair<FilterState, File>? = null

    fun start(illust: Illust, page: Int, previewMaxEdge: Int, thumbPx: Int) {
        if (this::illust.isInitialized) return
        this.illust = illust
        this.page = page
        load(previewMaxEdge, thumbPx)
        viewModelScope.launch(renderDispatcher) {
            _state.collectLatest { s ->
                val preview = (_load.value as? FilterLoad.Ready)?.preview ?: return@collectLatest
                // 参数在连续变（拖滑条）：先出一张草稿跟手，停手 SETTLE_MS 后再出清晰帧。
                // collectLatest 会在下一次变化时取消这里的 delay，所以拖动全程只渲草稿。
                val now = SystemClock.uptimeMillis()
                val dragging = now - lastChangeAt < DRAG_GAP_MS
                lastChangeAt = now
                val small = draft
                if (dragging && small != null) {
                    renderPreview(small, s, draftPool(small))
                    delay(SETTLE_MS)
                }
                renderPreview(preview, s, fullPool(preview))
            }
        }
    }

    fun retry(previewMaxEdge: Int, thumbPx: Int) {
        if (_load.value is FilterLoad.Failed) load(previewMaxEdge, thumbPx)
    }

    private fun load(previewMaxEdge: Int, thumbPx: Int) {
        loadJob?.cancel()
        _load.value = FilterLoad.Loading(null)
        loadJob = viewModelScope.launch {
            val preview = try {
                val file = awaitSource()
                source = file
                withContext(Dispatchers.IO) { decodePreview(file, previewMaxEdge) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[filter] load failed illust=%d page=%d", illust.id, page)
                null
            }
            if (preview == null) {
                _load.value = FilterLoad.Failed
                return@launch
            }
            _load.value = FilterLoad.Ready(preview)
            withContext(renderDispatcher) {
                draft = Bitmap.createScaledBitmap(
                    preview, (preview.width / 2).coerceAtLeast(1), (preview.height / 2).coerceAtLeast(1), true,
                )
                renderPreview(preview, _state.value, fullPool(preview))
                renderThumbs(preview, thumbPx)
            }
        }
    }

    private suspend fun awaitSource(): File {
        val url = IllustDownload.getUrl(illust, page, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, page, Params.IMAGE_RESOLUTION_LARGE)
            ?: error("no image url for page $page")
        val app = getApplication<Application>()
        // 复用看图页同一个共享下载任务（已加载直接拿到，没加载就等它下完），本地已下载这一页直接用本地文件。
        return when (val src = PageImageSourceResolver.resolve(app, illust, page, url)) {
            is PageImageSource.Remote -> src.task.awaitFile { _load.value = FilterLoad.Loading(it) }
            else -> src.awaitFile(app)
        } ?: error("page image unavailable: $url")
    }

    // ── 编辑 ────────────────────────────────────────────────────

    fun selectPreset(preset: FilterPreset) {
        val s = _state.value
        // 再点一次已选中的滤镜不改强度；换滤镜时强度回满，和修图 App 的习惯一致。
        if (s.preset == preset) return
        update(s.copy(preset = preset, intensity = 100))
    }

    fun setIntensity(value: Int) = update(_state.value.copy(intensity = value.coerceIn(0, 100)))

    fun setAdjust(a: FilterAdjust, value: Int) =
        update(_state.value.with(a, value.coerceIn(if (a.bipolar) -100 else 0, 100)))

    fun reset() = update(FilterState())

    /** 「重置」后的撤销。 */
    fun restore(s: FilterState) = update(s)

    /** 舞台换上了 [bitmap]；在它被换下之前，渲染不会再写这一只缓冲。 */
    fun onDisplayed(bitmap: Bitmap?) { displayed = bitmap }

    fun setAdjustMode(adjust: Boolean) { saved[KEY_MODE] = adjust }

    fun setAdjustTool(index: Int) { saved[KEY_TOOL] = index }

    private fun update(s: FilterState) {
        if (s == _state.value) return
        _state.value = s
        saved[KEY_PRESET] = s.preset.key
        saved[KEY_INTENSITY] = s.intensity
        saved[KEY_ADJUST] = s.adjust.toIntArray()
    }

    private fun restoreState(): FilterState {
        val adjust = saved.get<IntArray>(KEY_ADJUST)?.takeIf { it.size == FilterAdjust.entries.size }
        return FilterState(
            preset = FilterPreset.fromKey(saved.get<String>(KEY_PRESET)),
            intensity = saved.get<Int>(KEY_INTENSITY) ?: 100,
            adjust = adjust?.toList() ?: List(FilterAdjust.entries.size) { 0 },
        )
    }

    // ── 渲染 ────────────────────────────────────────────────────

    private fun fullPool(preview: Bitmap): Array<Bitmap> = buffers ?: newPool(preview).also { buffers = it }

    private fun draftPool(small: Bitmap): Array<Bitmap> = draftBuffers ?: newPool(small).also { draftBuffers = it }

    private fun newPool(like: Bitmap) = Array(BUFFER_COUNT) {
        Bitmap.createBitmap(like.width, like.height, Bitmap.Config.ARGB_8888)
    }

    /** 把 [s] 从 [source] 渲进 [pool] 里一只空闲的缓冲并发布；参数等同原图时直接发布预览小图。 */
    private fun renderPreview(source: Bitmap, s: FilterState, pool: Array<Bitmap>) {
        if (s.colorIsIdentity && s.vignette() == 0f && s.grain() == 0f) {
            _rendered.value = (_load.value as? FilterLoad.Ready)?.preview ?: source
            return
        }
        val shown = displayed
        val published = _rendered.value
        // 三只缓冲、最多两只被占（挂在舞台上的、刚发布还没被舞台取走的），总有一只是空闲的。
        val out = pool.first { it !== shown && it !== published }
        runCatching { NativeImageFilter.render(source, out, s) }
            .onSuccess { _rendered.value = out }
            .onFailure { Timber.e(it, "[filter] preview render failed") }
    }

    private fun renderThumbs(preview: Bitmap, thumbPx: Int) {
        val side = min(preview.width, preview.height)
        // 长条漫画居中裁出来的往往是对白和空白，取上三分之一处更像「这张图」。
        val top = if (preview.height > preview.width * 2) (preview.height - side) / 3 else (preview.height - side) / 2
        val crop = Bitmap.createBitmap(preview, (preview.width - side) / 2, top, side, side)
        val thumb = Bitmap.createScaledBitmap(crop, thumbPx, thumbPx, true)
        if (crop !== preview && crop !== thumb) crop.recycle()
        val out = MutableList<Bitmap?>(FilterPreset.entries.size) { null }
        for (preset in FilterPreset.entries) {
            val s = FilterState(preset = preset)
            out[preset.ordinal] = if (preset == FilterPreset.ORIGINAL) thumb else runCatching {
                Bitmap.createBitmap(thumbPx, thumbPx, Bitmap.Config.ARGB_8888).also {
                    NativeImageFilter.render(thumb, it, s, FilterLut.forPreset(preset))
                }
            }.onFailure { Timber.w(it, "[filter] thumb %s failed", preset.key) }.getOrNull()
            _thumbs.value = out.toList()
        }
    }

    private fun decodePreview(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        // inSampleSize 只能折半；再精确缩到舞台尺寸，预览每一帧要渲的像素越少越跟手。
        val longEdge = max(decoded.width, decoded.height)
        if (longEdge <= maxEdge) return decoded.toArgb8888(mutable = false)
        val k = maxEdge / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * k).roundToInt().coerceAtLeast(1),
            (decoded.height * k).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled.toArgb8888(mutable = false)
    }

    /**
     * 16 位 PNG 不认 inPreferredConfig：BitmapFactory 照样解成 RGBA_F16（Skia 对高精度源的默认），
     * 而 native 内核只吃 ARGB_8888。不转的话预览每帧都渲染失败、滤镜条全空、导出报错。转完回收原图。
     */
    private fun Bitmap.toArgb8888(mutable: Boolean): Bitmap? {
        if (config == Bitmap.Config.ARGB_8888) return this
        return copy(Bitmap.Config.ARGB_8888, mutable).also { recycle() }
    }

    // ── 导出 ────────────────────────────────────────────────────

    fun export(kind: FilterExport) {
        if (_exporting.value != null || _load.value !is FilterLoad.Ready) return
        val s = _state.value
        _exporting.value = kind
        viewModelScope.launch {
            val result = try {
                val file = renderFull(s)
                when (kind) {
                    FilterExport.SAVE -> {
                        saveToGallery(file, s)
                        FilterExportResult.Saved
                    }
                    FilterExport.SHARE -> {
                        val app = getApplication<Application>()
                        FilterExportResult.Share(
                            FileProvider.getUriForFile(app, "${app.packageName}.provider", file),
                            if (file.extension == "png") "image/png" else "image/jpeg",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OOM 也接住：超大图解码失败只是这一次导出失败，不该让页面崩掉。
                Timber.e(e, "[filter] export failed illust=%d page=%d", illust.id, page)
                FilterExportResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            _exporting.value = null
            _results.send(result)
        }
    }

    /** 全分辨率渲染并编码到 FileProvider 暴露的缓存目录；同一组参数只算一次。 */
    private suspend fun renderFull(s: FilterState): File {
        lastExport?.let { (state, file) -> if (state == s && file.exists()) return file }
        val src = source ?: error("source not loaded")
        val app = getApplication<Application>()
        val file = withContext(Dispatchers.Default) {
            val bitmap = decodeBoundedArgb(src, "filter", mutable = true)?.toArgb8888(mutable = true)
                ?: error("decode failed")
            try {
                NativeImageFilter.render(bitmap, bitmap, s)
                val png = bitmap.hasAlpha()
                val dir = File(app.externalCacheDir ?: app.cacheDir, "images").apply { mkdirs() }
                val out = File(dir, "${illust.id}_p${page}_${s.fileTag()}.${if (png) "png" else "jpg"}")
                val tmp = File(dir, out.name + ".part")
                FileOutputStream(tmp).buffered().use { stream ->
                    val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    check(bitmap.compress(format, JPEG_QUALITY, stream)) { "encode failed" }
                }
                check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "rename failed" }
                out
            } finally {
                bitmap.recycle()
            }
        }
        // 旧成品不删：可能刚分享出去，对方 App 还没读完。缓存目录交给系统回收。
        lastExport = s to file
        return file
    }

    private suspend fun saveToGallery(file: File, s: FilterState) = withContext(Dispatchers.IO) {
        val png = file.extension == "png"
        // open 返回 null = 用户选了「跳过已存在」且同名文件在：算已保存。
        val handle = DownloadsRegistry.downloads.openDerived(
            DownloadItems.illustFiltered(illust, page, png),
            "_filter_${s.fileTag()}",
        ) ?: return@withContext
        try {
            handle.stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            handle.onFinish()
        } catch (t: Throwable) {
            handle.onAbort()
            throw t
        }
    }

    private companion object {
        const val BUFFER_COUNT = 3

        /** 两次参数变化间隔小于它就算在拖动。 */
        const val DRAG_GAP_MS = 90L

        /** 停手多久后补一张清晰帧。 */
        const val SETTLE_MS = 120L
        const val JPEG_QUALITY = 95
        const val KEY_PRESET = "filter_preset"
        const val KEY_INTENSITY = "filter_intensity"
        const val KEY_ADJUST = "filter_adjust"
        const val KEY_MODE = "filter_mode_adjust"
        const val KEY_TOOL = "filter_adjust_tool"
    }
}
