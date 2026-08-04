package ceui.lisa.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.asLiveData
import ceui.pixiv.ui.translate.BubbleAreaFinder
import ceui.pixiv.ui.translate.ComicTextDetector
import ceui.pixiv.ui.translate.ComicTextDetectorModel
import ceui.pixiv.ui.translate.GoogleWebTranslator
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.translate.MangaOcrRecognizer
import ceui.pixiv.ui.translate.TextEraser
import ceui.pixiv.ui.translate.TextMask
import ceui.pixiv.ui.translate.TextRenderer
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.promptProxyNeededIfPossible
import ceui.pixiv.ui.upscale.MangaOcr
import ceui.pixiv.ui.upscale.OcrTextRegion
import ceui.pixiv.ui.upscale.scaledBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 二级详情「翻译漫画」一站式 pipeline:OCR → Google batch 翻译 → 译文回填到原图气泡位置 →
 * 把产物图路径喂回 [translatedPaths],由 FragmentImageDetail 替换显示。
 *
 * 设计:
 * - [running] 防重入 — 同一时间只跑一个 pipeline,UI 看着标志决定 toast 拦截
 * - [status] 单一来源驱动 overlay UI(文字 + 进度环);null 表示无任务,UI 隐藏 overlay
 * - [translatedPaths] pageIndex → 译图路径,Fragment 观察后切图
 * - 大图自动 downsample(短边 ≤ [MAX_RENDER_SHORT_SIDE])防 OOM,region 坐标等比缩放跟随
 * - 同一页二次翻译会把旧产物文件 delete 掉,避免 cacheDir 累积
 */
class ImageTranslationViewModel : ViewModel() {

    data class Status(
        val text: String,
        /** null = indeterminate 转圈,否则 0..100 */
        val progressPercent: Int? = null,
    )

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> get() = _running.asLiveData()

    private val _status = MutableLiveData<Status?>(null)
    val status: LiveData<Status?> get() = _status.asLiveData()

    private val _translatedPaths = MutableLiveData<Map<Int, String>>(emptyMap())
    val translatedPaths: LiveData<Map<Int, String>> get() = _translatedPaths.asLiveData()

    /**
     * 「圈选翻译」请求事件:Activity 菜单点了之后塞进目标 pageIndex,对应那页的
     * [ceui.lisa.fragments.FragmentImageDetail] 观察到自己 index 命中就进圈选模式,
     * 进完立刻 [consumeManualSelectionRequest] 置空防止旋转/重订阅重复触发。
     * 用 activity-scoped VM 单一来源派发,避免 Activity 直接持 Fragment 引用。
     */
    private val _manualSelectionRequest = MutableLiveData<Int?>(null)
    val manualSelectionRequest: LiveData<Int?> get() = _manualSelectionRequest.asLiveData()

    fun requestManualSelection(pageIndex: Int) {
        _manualSelectionRequest.value = pageIndex
    }

    fun consumeManualSelectionRequest() {
        _manualSelectionRequest.value = null
    }

    /**
     * 启动 pipeline。已在跑就直接 return false,UI 自己决定要不要 toast。
     */
    fun start(
        context: Context,
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ): Boolean {
        if (_running.value == true) return false
        _running.value = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                runPipeline(app, imageFile, pageIndex, ocrModel, ctdModel)
            } catch (e: Exception) {
                Timber.e(e, "ImageTranslationVM: pipeline failed")
                Common.showToast(R.string.string_ai_manga_translate_failed)
            } finally {
                _status.postValue(null)
                _running.postValue(false)
            }
        }
        return true
    }

    private suspend fun runPipeline(
        app: Context,
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ) {
        // 1. 模型按需加载
        if (!MangaOcrRecognizer.isLoaded || !ComicTextDetector.isLoaded) {
            _status.postValue(Status(app.getString(R.string.string_ai_ocr_loading_model)))
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    MangaOcrRecognizer.loadModel(app, ocrModel)
                    ComicTextDetector.loadModel(app, ctdModel)
                }.onFailure { Timber.e(it, "loadModel failed") }.isSuccess
            }
            if (!loaded) {
                Common.showToast(R.string.string_ai_ocr_failed)
                return
            }
        }

        // 2. OCR
        val ocrResult = MangaOcr.recognize(app, imageFile) { stage, fraction ->
            val pct = if (fraction.isNaN()) null else (fraction * 100).toInt().coerceIn(0, 100)
            _status.postValue(Status(stage, pct))
        }
        val regions = ocrResult?.regions
        if (regions.isNullOrEmpty()) {
            Common.showToast(
                if (ocrResult == null) R.string.string_ai_ocr_failed
                else R.string.string_ai_ocr_empty
            )
            return
        }

        // 3. Google batch 翻译 — 一次 POST 打包全部 region,中途没有有意义的进度,
        //    所以只 post 一个 indeterminate 状态盖住几秒 HTTP 等待,不再每 chunk 闪 N/N
        _status.postValue(Status(app.getString(R.string.ocr_translating)))
        val translations = mutableMapOf<Int, String>()
        try {
            GoogleWebTranslator.translateBatch(
                inputs = regions.map { it.text },
                outputLang = appTranslateTargetLang(),
                onItem = { i, translated -> translations[i] = translated },
            )
        } catch (e: Exception) {
            // Google Translate 在国内被墙,大概率是代理没开;给个明确提示别让用户当 app bug
            Timber.e(e, "GoogleWebTranslator.translateBatch failed")
            promptProxyNeededIfPossible()
            return
        }
        if (translations.isEmpty()) {
            // batch 走完了但一条没回 — 多半也是代理半通不通(per-item fallback 全失败)
            promptProxyNeededIfPossible()
            return
        }

        // 4. 回填
        _status.postValue(Status(app.getString(R.string.ocr_writeback_running)))
        val outFile = withContext(Dispatchers.IO) {
            runCatching {
                renderTranslated(app, imageFile, pageIndex, regions, translations, ocrResult.textMask, ocrResult.ocrSample)
            }.onFailure { Timber.e(it, "renderTranslated failed") }.getOrNull()
        }
        if (outFile == null) {
            Common.showToast(R.string.string_ai_manga_translate_failed)
            return
        }

        // 5. 发布新译图路径,并清掉同 page 的旧产物
        publishTranslated(pageIndex, outFile.absolutePath)
    }

    /**
     * 「圈选翻译」入口(issue #891):用户手动框一块区域,只翻这一块。补自动检测漏掉的
     * 无气泡文本。坐标是相对**当前显示图**的归一化 [0,1] 矩形(由 Fragment 用 zoomimage
     * 的 contentSize 换算好),与显示图分辨率无关。
     *
     * 与自动流水线的关键差异:
     * - 不跑 CTD 检测,直接 crop 用户框 → manga-ocr 单框识别,所以只需 OCR 模型。
     * - **叠加而非覆盖**:若本页已有译图(自动翻译产物或上一次圈选产物),就在那张图上继续
     *   擦字回填,这样多次圈选 + 自动翻译能累积到同一张图;否则落在原图上。
     * - 不做气泡扩展:用户框多大就在多大区域内排版,所见即所得。
     */
    fun startManualRegion(
        context: Context,
        originalFile: File,
        pageIndex: Int,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        ocrModel: MangaOcrModel,
    ): Boolean {
        if (_running.value == true) return false
        _running.value = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                runManualPipeline(app, originalFile, pageIndex, normLeft, normTop, normRight, normBottom, ocrModel)
            } catch (e: Exception) {
                Timber.e(e, "ImageTranslationVM: manual pipeline failed")
                Common.showToast(R.string.string_ai_manga_translate_failed)
            } finally {
                _status.postValue(null)
                _running.postValue(false)
            }
        }
        return true
    }

    private suspend fun runManualPipeline(
        app: Context,
        originalFile: File,
        pageIndex: Int,
        l: Float, t: Float, r: Float, b: Float,
        ocrModel: MangaOcrModel,
    ) {
        // 1. 只需 manga-ocr 模型(CTD 仅自动检测用),按需加载
        if (!MangaOcrRecognizer.isLoaded) {
            _status.postValue(Status(app.getString(R.string.string_ai_ocr_loading_model)))
            val ok = withContext(Dispatchers.IO) {
                runCatching { MangaOcrRecognizer.loadModel(app, ocrModel) }
                    .onFailure { Timber.e(it, "manual: loadModel failed") }.isSuccess
            }
            if (!ok) {
                Common.showToast(R.string.string_ai_ocr_failed)
                return
            }
        }

        // 2. 选底图:已有译图就在它上面继续叠,否则落原图。Fragment 也是按 translatedPaths
        //    决定当前显示哪张,两边口径一致 → 归一化坐标必然对齐。
        val baseFile = _translatedPaths.value?.get(pageIndex)
            ?.let { File(it) }?.takeIf { it.exists() } ?: originalFile

        // 3. 解码底图 + crop 选区 + 单框 OCR(全在 IO)
        _status.postValue(Status(app.getString(R.string.string_ai_manga_manual_recognizing)))
        val ocr = withContext(Dispatchers.IO) {
            runCatching { recognizeManualRegion(baseFile, l, t, r, b) }
                .onFailure { Timber.e(it, "manual: recognize failed") }.getOrNull()
        }
        if (ocr == null) {
            Common.showToast(R.string.string_ai_manga_translate_failed)
            return
        }
        try {
            if (ocr.text.isBlank()) {
                Common.showToast(R.string.string_ai_ocr_empty)
                return
            }

            // 4. 翻译(单条)
            _status.postValue(Status(app.getString(R.string.ocr_translating)))
            val translated = try {
                translateSingle(ocr.text)
            } catch (e: Exception) {
                Timber.e(e, "manual: translate failed")
                promptProxyNeededIfPossible()
                return
            }
            if (translated.isBlank()) {
                promptProxyNeededIfPossible()
                return
            }

            // 5. 擦字 + 回填到底图,产出新 PNG
            _status.postValue(Status(app.getString(R.string.ocr_writeback_running)))
            val outFile = withContext(Dispatchers.IO) {
                runCatching { renderManualOnto(app, ocr.base, pageIndex, ocr.region.copy(text = ocr.text), translated) }
                    .onFailure { Timber.e(it, "manual: render failed") }.getOrNull()
            }
            if (outFile == null) {
                Common.showToast(R.string.string_ai_manga_translate_failed)
                return
            }
            publishTranslated(pageIndex, outFile.absolutePath)
        } finally {
            ocr.base.recycle()
        }
    }

    /** 圈选 OCR 的中间产物:[base] 是解码出的底图(调用方负责 recycle)。 */
    private class ManualOcr(val base: Bitmap, val region: OcrTextRegion, val text: String)

    /**
     * 解码底图(短边降采样到 [MAX_RENDER_SHORT_SIDE] 防 OOM)→ 把归一化矩形换算成像素框 →
     * crop 出来喂 manga-ocr。region 直接构造在「底图像素坐标系」下,后续擦/填都在这套坐标里,
     * 不再有 sample 还原那一层。框太小 / 解码失败返回 null。
     */
    private fun recognizeManualRegion(file: File, l: Float, t: Float, r: Float, b: Float): ManualOcr? {
        val base = decodeSampled(file, MAX_RENDER_SHORT_SIDE) ?: return null
        var keep = false
        try {
            val w = base.width
            val h = base.height
            val x0 = (l * w).toInt().coerceIn(0, w - 1)
            val y0 = (t * h).toInt().coerceIn(0, h - 1)
            val x1 = (r * w).toInt().coerceIn(x0 + 1, w)
            val y1 = (b * h).toInt().coerceIn(y0 + 1, h)
            val rw = x1 - x0
            val rh = y1 - y0
            if (rw < MIN_MANUAL_REGION_PX || rh < MIN_MANUAL_REGION_PX) return null

            // createBitmap 在「子区域==整图且 base 不可变」时会直接返回 base 本身;
            // 此时绝不能 recycle,否则把底图也回收了,后续 eraseText 直接挂。
            val crop = Bitmap.createBitmap(base, x0, y0, rw, rh)
            val result = try {
                MangaOcrRecognizer.recognize(crop)
            } finally {
                if (crop !== base) crop.recycle()
            }
            val region = OcrTextRegion(
                text = result.text,
                cx = x0 + rw / 2f,
                cy = y0 + rh / 2f,
                width = rw.toFloat(),
                height = rh.toFloat(),
                angle = 0f,
                orientation = 0,
                prob = 1f,
                corners = listOf(
                    x0.toFloat() to y0.toFloat(),
                    x1.toFloat() to y0.toFloat(),
                    x1.toFloat() to y1.toFloat(),
                    x0.toFloat() to y1.toFloat(),
                ),
                recogConfidence = result.confidence,
            )
            keep = true
            return ManualOcr(base, region, result.text.trim())
        } finally {
            if (!keep) base.recycle()
        }
    }

    private suspend fun translateSingle(text: String): String {
        var out = ""
        GoogleWebTranslator.translateBatch(
            inputs = listOf(text),
            outputLang = appTranslateTargetLang(),
            onItem = { _, translated -> out = translated },
        )
        return out
    }

    /**
     * 在底图上擦掉选区原文(无 mask,走颜色阈值兜底)再把译文排进**用户框定的区域**,
     * 存成新 PNG。不做气泡扩展 —— 圈选场景所见即所得。
     */
    private fun renderManualOnto(
        app: Context,
        base: Bitmap,
        pageIndex: Int,
        region: OcrTextRegion,
        translated: String,
    ): File {
        val erased = TextEraser.eraseText(base, listOf(region), null)
        try {
            val canvas = Canvas(erased)
            TextRenderer.renderTranslations(canvas, listOf(region), mapOf(0 to translated))
            val out = File(
                app.cacheDir,
                "manga_translated_p${pageIndex}_${System.currentTimeMillis()}.png"
            )
            FileOutputStream(out).use { erased.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Timber.d("ManualWriteBack: saved → %s", out.absolutePath)
            return out
        } finally {
            erased.recycle()
        }
    }

    /** 短边降采样解码,短边压到 [maxShort] 以内防 OOM。与自动流水线同口径。 */
    private fun decodeSampled(file: File, maxShort: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortSide / sample > maxShort) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    /**
     * 解码原图(短边自动降采样到 [MAX_RENDER_SHORT_SIDE] 以内防 OOM),
     * 然后按降采样比例同步缩放 region 坐标系再喂给 TextEraser/TextRenderer。
     *
     * 入参 [regions] 必须是"原图坐标系"的(契约见 [OcrTextRegion]),所以这里把它们除以
     * 本次 decode 用的 sample 即可对齐到 bitmap 像素。
     *
     * [textMask] 是 OCR 阶段拿到的像素级文本 mask,坐标系按 [ocrSample] 解出的 bitmap。
     * 如果 ocrSample 跟我们这里算的 renderSample 一致(常态:两边阈值同为 2400),
     * 直接传给 TextEraser;否则 mask 对不齐 → 丢掉走 fallback,不强行 resample。
     */
    private fun renderTranslated(
        app: Context,
        imageFile: File,
        pageIndex: Int,
        regions: List<OcrTextRegion>,
        translations: Map<Int, String>,
        textMask: TextMask?,
        ocrSample: Int,
    ): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "decode bounds failed" }

        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortSide / sample > MAX_RENDER_SHORT_SIDE) sample *= 2

        val original = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
        ) ?: error("decode failed: ${imageFile.absolutePath}")

        Timber.d(
            "WriteBack: orig %dx%d sample=%d → bitmap %dx%d; %d regions, %d with translation",
            bounds.outWidth, bounds.outHeight, sample, original.width, original.height,
            regions.size, translations.size
        )
        if (regions.isNotEmpty()) {
            val r0 = regions[0]
            Timber.d(
                "WriteBack: region[0] (orig coords) cx=%.0f cy=%.0f w=%.0f h=%.0f orient=%d",
                r0.cx, r0.cy, r0.width, r0.height, r0.orientation
            )
        }

        try {
            val scaleFactor = 1f / sample
            val scaledRegions = if (sample == 1) regions else regions.map { it.scaledBy(scaleFactor) }
            if (sample > 1 && scaledRegions.isNotEmpty()) {
                val s0 = scaledRegions[0]
                Timber.d(
                    "WriteBack: scaledRegion[0] (bitmap coords) cx=%.0f cy=%.0f w=%.0f h=%.0f",
                    s0.cx, s0.cy, s0.width, s0.height
                )
            }
            // 只擦"有译文"的 region,失败项保留日文原貌
            val toErase = scaledRegions.filterIndexed { i, _ -> !translations[i].isNullOrBlank() }
            // mask 跟 OCR bitmap 一致;只有 ocrSample==renderSample 且 dim 也对得上才喂给 eraser
            val maskForEraser = textMask?.takeIf {
                ocrSample == sample && it.width == original.width && it.height == original.height
            }
            if (textMask != null && maskForEraser == null) {
                Timber.w(
                    "WriteBack: mask drop — ocrSample=%d renderSample=%d, maskDim=%dx%d bitmap=%dx%d",
                    ocrSample, sample, textMask.width, textMask.height, original.width, original.height
                )
            }
            val erased = TextEraser.eraseText(original, toErase, maskForEraser)
            try {
                val canvas = Canvas(erased)
                // 把每个有译文 region 的 corners 扩到气泡内部可写区域 —
                // OCR 框紧贴日文字符,远小于气泡,中文塞回去字号被压成蚂蚁;
                // 扩到气泡边界(BG 连通区域)后中文能用满整个气泡。
                val regionsForRender = scaledRegions.mapIndexed { i, region ->
                    if (translations[i].isNullOrBlank()) return@mapIndexed region
                    val bgColor = TextEraser.sampleBackgroundColor(erased, region)
                    val b = BubbleAreaFinder.expand(erased, region, bgColor)
                    region.copy(
                        corners = listOf(
                            b[0].toFloat() to b[1].toFloat(),
                            b[2].toFloat() to b[1].toFloat(),
                            b[2].toFloat() to b[3].toFloat(),
                            b[0].toFloat() to b[3].toFloat(),
                        )
                    )
                }
                TextRenderer.renderTranslations(canvas, regionsForRender, translations)
                val out = File(
                    app.cacheDir,
                    "manga_translated_p${pageIndex}_${System.currentTimeMillis()}.png"
                )
                FileOutputStream(out).use { erased.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Timber.d("WriteBack: saved → %s", out.absolutePath)
                return out
            } finally {
                erased.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private fun publishTranslated(pageIndex: Int, newPath: String) {
        val oldPath = _translatedPaths.value?.get(pageIndex)
        val nextMap = _translatedPaths.value.orEmpty().toMutableMap().apply {
            put(pageIndex, newPath)
        }
        _translatedPaths.postValue(nextMap)
        if (oldPath != null && oldPath != newPath) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(oldPath).delete() }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // VM 终结时把 cacheDir 里这次会话产生的译图全删掉
        val paths = _translatedPaths.value.orEmpty().values.toList()
        if (paths.isNotEmpty()) {
            // 不能用 viewModelScope(已 cancel),直接同步删
            paths.forEach { runCatching { File(it).delete() } }
        }
    }

    companion object {
        /** 回填渲染时图像短边的上限,2400 对齐 [MangaOcr.MAX_INPUT_SHORT_SIDE]。 */
        private const val MAX_RENDER_SHORT_SIDE = 2400

        /** 圈选框换算到底图像素后的最小边长,低于此判为误触/空框。 */
        private const val MIN_MANUAL_REGION_PX = 8
    }
}

