package ceui.lisa.activities

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.services.appServices
import ceui.pixiv.utils.asLiveData
import ceui.pixiv.ui.translate.ComicTextDetectorModel
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.translate.MangaBatchTranslateCenter
import ceui.pixiv.ui.translate.MangaPageTranslatePipeline
import ceui.pixiv.ui.translate.MangaPageTranslatePipeline.Stage
import ceui.pixiv.ui.translate.TextEraser
import ceui.pixiv.ui.translate.TextRenderer
import ceui.pixiv.ui.translate.appTranslateTargetLang
import ceui.pixiv.ui.translate.currentTranslator
import ceui.pixiv.ui.translate.promptTranslateFailedIfPossible
import ceui.pixiv.ui.upscale.OcrTextRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - 大图自动 downsample(短边 ≤ [MangaPageTranslatePipeline.MAX_RENDER_SHORT_SIDE])防 OOM,region 坐标等比缩放跟随
 * - 同一页二次翻译会把旧产物文件 delete 掉,避免 cacheDir 累积
 */
class ImageTranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Context = application
    private val services = application.appServices()
    private val batchCenter: MangaBatchTranslateCenter get() = services.mangaBatchTranslateCenter
    private val models get() = services.mangaTranslateModels

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> get() = _running.asLiveData()

    private val _status = MutableLiveData<Stage?>(null)
    val status: LiveData<Stage?> get() = _status.asLiveData()

    /**
     * 本作品 pageIndex → 译图路径。真正的存储在 [MangaBatchTranslateCenter](进程级,按作品 id 分桶),
     * 这样「翻译整部」在别的页面跑出来的译图、以及上次在本页单页翻的译图,重进看图页都还在;
     * 这里只是对当前绑定作品那一桶的投影。
     */
    private val _translatedPaths = MediatorLiveData<Map<Int, String>>().apply { value = emptyMap() }
    val translatedPaths: LiveData<Map<Int, String>> get() = _translatedPaths

    private var illustId: Long = 0L
    private var boundSource: LiveData<Map<Int, String>>? = null

    /** 看图页拿到作品后绑定;重复绑同一个 id 幂等。 */
    fun bindIllust(id: Long) {
        if (id == illustId && boundSource != null) return
        boundSource?.let { _translatedPaths.removeSource(it) }
        illustId = id
        val source = batchCenter.pathsOf(id)
        boundSource = source
        _translatedPaths.addSource(source) { _translatedPaths.value = it }
    }

    /** 当前正在跑的 pipeline job;页面销毁时用它及时取消工作流。 */
    private var pipelineJob: Job? = null

    /**
     * 页面销毁/用户离开触发的取消标记。置位后即使底层阻塞调用晚到的真实异常
     * (超时、断网、HTTP 错误)逃逸出来,也不再当成「翻译失败」弹给用户——
     * 那只是取消后的残留噪音,统一按取消语义处理。
     */
    private var cancelledByUser = false

    /** 「翻译已取消」toast 是否已弹过,避免 cancelActiveWorkflow 与协程收尾各弹一次。 */
    private var cancelToastShown = false

    /**
     * 是否已向 AI 接口发起请求(POST 即将送出,Token 可能已开始烧)。
     * 只在 AiTranslator 触发,Google 免费端点不会置位;置位后退出要二次确认。
     */
    @Volatile
    private var aiRequestSent = false

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
     * 页面销毁时调用:立即停掉当前 pipeline 并弹「翻译已取消」。
     * 已在跑才弹;没在跑(已成功/已失败)直接忽略,避免退出页面时误弹。
     */
    fun cancelActiveWorkflow() {
        val job = pipelineJob
        if (_running.value != true || job == null || !job.isActive) return
        cancelledByUser = true
        job.cancel()
        maybeToastCancelled()
    }

    /** 取消已由用户触发(页面销毁/离开)时补一次「翻译已取消」反馈;已弹过则跳过。 */
    private fun maybeToastCancelled() {
        if (!cancelToastShown) {
            cancelToastShown = true
            Common.showToast(R.string.string_ai_manga_translate_cancelled)
        }
    }

    /** AI 翻译已向接口发过 POST(有 Token 成本)且流水线仍在跑:退出前需要二次确认。 */
    fun shouldConfirmAiExit(): Boolean = _running.value == true && aiRequestSent

    /**
     * 启动 pipeline。已在跑就直接 return false,UI 自己决定要不要 toast。
     */
    fun start(
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ): Boolean {
        if (_running.value == true) return false
        if (!batchCenter.beginSinglePage()) return false
        _running.value = true
        cancelledByUser = false
        cancelToastShown = false
        aiRequestSent = false
        pipelineJob = viewModelScope.launch {
            try {
                val outcome = MangaPageTranslatePipeline.translatePage(
                    app, models, imageFile, pageIndex, ocrModel, ctdModel,
                    onStage = { _status.postValue(it) },
                    onRequestSent = { aiRequestSent = true },
                )
                if (outcome is MangaPageTranslatePipeline.Outcome.Done) {
                    publishTranslated(pageIndex, outcome.outFile.absolutePath)
                } else if (!cancelledByUser) {
                    reportSinglePageOutcome(outcome)
                }
            } catch (e: CancellationException) {
                // 页面/VM 销毁触发取消:必须重抛,不能当普通失败 toast,否则状态被吞。
                // 兜底弹「翻译已取消」——cancelActiveWorkflow 没走到(如系统销毁)时,
                // 协程收尾到这里才给用户反馈;已弹过则不重复。
                if (cancelledByUser) maybeToastCancelled()
                throw e
            } catch (e: Exception) {
                if (cancelledByUser) {
                    // 取消后晚到的阻塞调用异常:不是真实失败,不再弹「翻译失败」
                    Timber.d(e, "ImageTranslationVM: error after user cancelled, ignored")
                } else {
                    Timber.e(e, "ImageTranslationVM: pipeline failed")
                    Common.showToast(R.string.string_ai_manga_translate_failed)
                }
            } finally {
                batchCenter.endSinglePage()
                _status.postValue(null)
                _running.postValue(false)
                pipelineJob = null
            }
        }
        return true
    }

    /** 单页模式:把流水线结局翻成用户可见的 toast / 代理提示。 */
    private fun reportSinglePageOutcome(outcome: MangaPageTranslatePipeline.Outcome) {
        when (outcome) {
            is MangaPageTranslatePipeline.Outcome.Done -> Unit
            MangaPageTranslatePipeline.Outcome.ModelLoadFailed,
            MangaPageTranslatePipeline.Outcome.OcrFailed -> Common.showToast(R.string.string_ai_ocr_failed)
            MangaPageTranslatePipeline.Outcome.OcrEmpty -> Common.showToast(R.string.string_ai_ocr_empty)
            // 按引擎给明确提示(谷歌被墙 → 需要代理;AI → 真实错误),别让用户当 app bug
            is MangaPageTranslatePipeline.Outcome.TranslateFailed -> promptTranslateFailedIfPossible(outcome.error)
            MangaPageTranslatePipeline.Outcome.RenderFailed -> Common.showToast(R.string.string_ai_manga_translate_failed)
        }
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
        originalFile: File,
        pageIndex: Int,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        ocrModel: MangaOcrModel,
    ): Boolean {
        if (_running.value == true) return false
        if (!batchCenter.beginSinglePage()) return false
        _running.value = true
        cancelledByUser = false
        cancelToastShown = false
        aiRequestSent = false
        pipelineJob = viewModelScope.launch {
            try {
                runManualPipeline(originalFile, pageIndex, normLeft, normTop, normRight, normBottom, ocrModel)
            } catch (e: CancellationException) {
                if (cancelledByUser) maybeToastCancelled()
                throw e
            } catch (e: Exception) {
                if (cancelledByUser) {
                    Timber.d(e, "ImageTranslationVM: manual error after user cancelled, ignored")
                } else {
                    Timber.e(e, "ImageTranslationVM: manual pipeline failed")
                    Common.showToast(R.string.string_ai_manga_translate_failed)
                }
            } finally {
                batchCenter.endSinglePage()
                _status.postValue(null)
                _running.postValue(false)
                pipelineJob = null
            }
        }
        return true
    }

    private suspend fun runManualPipeline(
        originalFile: File,
        pageIndex: Int,
        l: Float, t: Float, r: Float, b: Float,
        ocrModel: MangaOcrModel,
    ) {
        // 1. 只需 manga-ocr 模型(CTD 仅自动检测用),按需加载
        if (!models.isOcrLoaded) {
            _status.postValue(Stage(app.getString(R.string.string_ai_ocr_loading_model)))
            if (!models.ensureOcrLoaded(ocrModel)) {
                if (!cancelledByUser) Common.showToast(R.string.string_ai_ocr_failed)
                return
            }
        }

        // 2. 选底图:已有译图就在它上面继续叠,否则落原图。Fragment 也是按 translatedPaths
        //    决定当前显示哪张,两边口径一致 → 归一化坐标必然对齐。
        val baseFile = _translatedPaths.value?.get(pageIndex)
            ?.let { File(it) }?.takeIf { it.exists() } ?: originalFile

        // 3. 解码底图 + crop 选区 + 单框 OCR(全在 IO)
        _status.postValue(Stage(app.getString(R.string.string_ai_manga_manual_recognizing)))
        val ocr = withContext(Dispatchers.IO) {
            try {
                recognizeManualRegion(baseFile, l, t, r, b)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "manual: recognize failed")
                null
            }
        }
        if (ocr == null) {
            if (!cancelledByUser) Common.showToast(R.string.string_ai_manga_translate_failed)
            return
        }
        try {
            if (ocr.text.isBlank()) {
                if (!cancelledByUser) Common.showToast(R.string.string_ai_ocr_empty)
                return
            }

            // 4. 翻译(单条)
            _status.postValue(Stage(app.getString(R.string.ocr_translating)))
            val translated = try {
                translateSingle(ocr.text, app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelledByUser) {
                    Timber.d(e, "ImageTranslationVM: manual translate error after user cancelled, ignored")
                } else {
                    Timber.e(e, "manual: translate failed")
                    promptTranslateFailedIfPossible(e)
                }
                return
            }
            if (translated.isBlank()) {
                if (!cancelledByUser) promptTranslateFailedIfPossible(null)
                return
            }

            // 5. 擦字 + 回填到底图,产出新 PNG
            _status.postValue(Stage(app.getString(R.string.ocr_writeback_running)))
            val outFile = withContext(Dispatchers.IO) {
                runCatching { renderManualOnto(app, ocr.base, pageIndex, ocr.region.copy(text = ocr.text), translated) }
                    .onFailure { Timber.e(it, "manual: render failed") }.getOrNull()
            }
            if (outFile == null) {
                if (!cancelledByUser) Common.showToast(R.string.string_ai_manga_translate_failed)
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
     * 解码底图(短边降采样到 [MangaPageTranslatePipeline.MAX_RENDER_SHORT_SIDE] 防 OOM)→ 把归一化矩形换算成像素框 →
     * crop 出来喂 manga-ocr。region 直接构造在「底图像素坐标系」下,后续擦/填都在这套坐标里,
     * 不再有 sample 还原那一层。框太小 / 解码失败返回 null。
     */
    private suspend fun recognizeManualRegion(file: File, l: Float, t: Float, r: Float, b: Float): ManualOcr? {
        val base = decodeSampled(file, MangaPageTranslatePipeline.MAX_RENDER_SHORT_SIDE) ?: return null
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
                models.ocr.recognize(crop)
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

    private suspend fun translateSingle(text: String, app: Context): String {
        var out = ""
        currentTranslator().translateBatch(
            inputs = listOf(text),
            outputLang = appTranslateTargetLang(),
            onItem = { _, translated -> out = translated },
            onPhase = { phase -> _status.postValue(MangaPageTranslatePipeline.translatePhaseStage(app, phase)) },
            onRequestSent = { aiRequestSent = true },
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

    private fun publishTranslated(pageIndex: Int, newPath: String) {
        batchCenter.publish(illustId, pageIndex, newPath)
    }

    override fun onCleared() {
        super.onCleared()
        // VM 终结(Activity 销毁)时同样按取消处理:viewModelScope 已 cancel,
        // 但阻塞调用可能还在跑,晚到的异常不能误报「翻译失败」。
        // 译图文件归 MangaBatchTranslateCenter 管(按作品 LRU 清理),这里不删。
        cancelledByUser = true
        pipelineJob?.cancel()
    }

    companion object {
        /** 圈选框换算到底图像素后的最小边长,低于此判为误触/空框。 */
        private const val MIN_MANUAL_REGION_PX = 8
    }
}

