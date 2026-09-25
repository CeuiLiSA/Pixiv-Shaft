package ceui.pixiv.ui.translate

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import ceui.lisa.R
import ceui.pixiv.ui.upscale.MangaOcr
import ceui.pixiv.ui.upscale.OcrTextRegion
import ceui.pixiv.ui.upscale.scaledBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 一页漫画的自动翻译流水线:模型按需加载 → CTD+OCR → batch 翻译 → 擦字回填 → 落盘 PNG。
 *
 * 单页「翻译漫画」([ceui.lisa.activities.ImageTranslationViewModel])和整部批量
 * ([MangaBatchTranslateCenter])共用这一份;本对象无状态(纯函数集合,所以是 object),
 * 不弹任何 toast、不持任何 UI 状态,模型会话由调用方通过 [MangaTranslateModels] 传入,
 * 阶段进度通过 [onStage] 吐出,结局用 [Outcome] 返回,由调用方决定怎么提示用户。
 * 取消(CancellationException)原样上抛。
 */
object MangaPageTranslatePipeline {

    /** 阶段文案 + 可选百分比(null = indeterminate)。 */
    data class Stage(
        val text: String,
        val progressPercent: Int? = null,
    )

    /** 一页流水线的结局。 */
    sealed class Outcome {
        class Done(val outFile: File) : Outcome()
        object ModelLoadFailed : Outcome()
        object OcrFailed : Outcome()
        object OcrEmpty : Outcome()
        /** [error] 为 null = batch 走完了但一条没回(Google 多半是代理半通不通,per-item fallback 全失败) */
        class TranslateFailed(val error: Exception?) : Outcome()
        object RenderFailed : Outcome()
    }

    /**
     * 擦字 / 气泡扩展 / 排版里的像素常量是在「短边 ≤ 这个值」的图上调的(与 OCR 输入上限一致)。
     * 回填本身尽量在原分辨率上做(译图要能当高清原图下载),超出这个密度的部分用
     * [RenderBase.pxScale] 把常量等比放大,排版观感与分辨率无关。
     */
    const val LAYOUT_REFERENCE_SHORT_SIDE = 2400

    /** 回填最多占用可用内存的这个比例;整页位图超了才降采样,留余量给看图页和系统。 */
    private const val RENDER_MEMORY_FRACTION = 0.5

    /**
     * 回填用的底图。
     * @property sample 相对原图文件的 inSampleSize,1 = 原分辨率
     * @property pxScale 相对 [LAYOUT_REFERENCE_SHORT_SIDE] 的像素密度倍数,喂给擦字 / 排版放大像素常量
     */
    class RenderBase(val bitmap: Bitmap, val sample: Int, val pxScale: Float)

    /**
     * @param onRequestSent AI 引擎即将发 POST(Token 开始烧)时回调,调用方用来决定退出要不要二次确认
     */
    suspend fun translatePage(
        app: Context,
        models: MangaTranslateModels,
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
        onStage: (Stage) -> Unit,
        onRequestSent: () -> Unit = {},
    ): Outcome {
        // 1. 模型按需加载
        if (!models.isLoaded) {
            onStage(Stage(app.getString(R.string.string_ai_ocr_loading_model)))
            if (!models.ensureLoaded(ocrModel, ctdModel)) return Outcome.ModelLoadFailed
        }

        // 2. OCR
        val ocrResult = MangaOcr.recognize(app, models, imageFile) { stage, fraction ->
            val pct = if (fraction.isNaN()) null else (fraction * 100).toInt().coerceIn(0, 100)
            onStage(Stage(stage, pct))
        } ?: return Outcome.OcrFailed
        val regions = ocrResult.regions
        if (regions.isEmpty()) return Outcome.OcrEmpty

        // 3. batch 翻译(Google web 或自定义 AI 引擎 #975)— 一次请求打包全部 region,
        //    中途没有有意义的进度,所以只 post 一个 indeterminate 状态盖住 HTTP 等待,不再每 chunk 闪 N/N
        onStage(Stage(app.getString(R.string.ocr_translating)))
        val translations = mutableMapOf<Int, String>()
        try {
            currentTranslator().translateBatch(
                inputs = regions.map { it.text },
                outputLang = appTranslateTargetLang(),
                onItem = { i, translated -> translations[i] = translated },
                onPhase = { phase -> onStage(translatePhaseStage(app, phase)) },
                onRequestSent = onRequestSent,
            )
        } catch (e: CancellationException) {
            // 离开页面/重新进入导致协程取消:重抛,别把「Job was cancelled」当真实错误弹给用户
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translateBatch failed")
            return Outcome.TranslateFailed(e)
        }
        if (translations.isEmpty()) return Outcome.TranslateFailed(null)

        // 4. 回填
        onStage(Stage(app.getString(R.string.ocr_writeback_running)))
        val outFile = withContext(Dispatchers.IO) {
            runCatching {
                renderTranslated(app, imageFile, pageIndex, regions, translations, ocrResult.textMask)
            }.onFailure { Timber.e(it, "renderTranslated failed") }.getOrNull()
        } ?: return Outcome.RenderFailed
        return Outcome.Done(outFile)
    }

    /** 优先展示上游状态；开始出译文后恢复翻译中，不把 reasoning 写入译文。 */
    fun translatePhaseStage(app: Context, phase: AiTranslatePhase): Stage =
        Stage(phase.statusText(app))

    /**
     * 以原分辨率解码回填底图(mutable,擦字和排版原地在它上面做,整页只占一份位图);
     * 整页放不进 [renderMemoryBudget] 或解码 OOM 时才按 2 的幂降采样。解码失败返回 null。
     * 自动回填与圈选回填共用,两边出图分辨率一致。
     */
    fun decodeRenderBase(app: Context, file: File): RenderBase? =
        decodeRenderBase(file, renderMemoryBudget(app))

    internal fun decodeRenderBase(file: File, budgetBytes: Long): RenderBase? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val minSide = minOf(w, h)
        var sample = 1
        while (4L * (w / sample) * (h / sample) > budgetBytes && minSide / sample > 1) sample *= 2
        var bitmap: Bitmap? = null
        while (bitmap == null) {
            try {
                bitmap = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                        inSampleSize = sample
                    }
                ) ?: return null
            } catch (oom: OutOfMemoryError) {
                // 预算只是估计;真解不下就再降一档,别让 OOM 冲出流水线把进程带崩
                if (minSide / sample <= 1) throw oom
                Timber.w(oom, "RenderBase: decode OOM at sample=%d, retry lower", sample)
                sample *= 2
            }
        }

        var referenceSample = 1
        while (minSide / referenceSample > LAYOUT_REFERENCE_SHORT_SIDE) referenceSample *= 2
        val pxScale = referenceSample.toFloat() / sample
        Timber.d(
            "RenderBase: orig %dx%d sample=%d → %dx%d pxScale=%.2f (budget %d MB)",
            w, h, sample, bitmap.width, bitmap.height, pxScale, budgetBytes shr 20,
        )
        return RenderBase(bitmap, sample, pxScale)
    }

    /**
     * 整页位图可用的内存预算。8.0+ 位图像素在 native 内存,不占 Java 堆,上限看系统可用内存
     * (扣掉低内存杀进程阈值);7.x 位图还在 Java 堆里,看堆余量。
     */
    private fun renderMemoryBudget(app: Context): Long {
        val available = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val info = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            info.availMem - info.threshold
        } else {
            val rt = Runtime.getRuntime()
            rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        }
        return (available.coerceAtLeast(0L) * RENDER_MEMORY_FRACTION).toLong()
    }

    /**
     * 在 [decodeRenderBase] 的底图上回填,并按底图 sample 缩放 region 坐标系再喂给 TextEraser/TextRenderer。
     *
     * 入参 [regions] 必须是"原图坐标系"的(契约见 [OcrTextRegion]),所以这里把它们除以
     * 本次 decode 用的 sample 即可对齐到 bitmap 像素。
     *
     * [textMask] 是 OCR 阶段拿到的像素级文本 mask,分辨率是 OCR 降采样图的;
     * 底图分辨率更高时 TextEraser 按比例取样,不需要两边 sample 一致。
     *
     * internal 供单测验证「译图 = 原图尺寸 + 译文」。
     */
    internal fun renderTranslated(
        app: Context,
        imageFile: File,
        pageIndex: Int,
        regions: List<OcrTextRegion>,
        translations: Map<Int, String>,
        textMask: TextMask?,
    ): File {
        val base = decodeRenderBase(app, imageFile) ?: error("decode failed: ${imageFile.absolutePath}")
        val bitmap = base.bitmap
        val sample = base.sample
        val pxScale = base.pxScale

        Timber.d(
            "WriteBack: bitmap %dx%d sample=%d; %d regions, %d with translation",
            bitmap.width, bitmap.height, sample, regions.size, translations.size
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
            TextEraser.eraseText(bitmap, toErase, textMask, pxScale)
            val canvas = Canvas(bitmap)
            // 把每个有译文 region 的 corners 扩到气泡内部可写区域 —
            // OCR 框紧贴日文字符,远小于气泡,中文塞回去字号被压成蚂蚁;
            // 扩到气泡边界(BG 连通区域)后中文能用满整个气泡。
            val regionsForRender = scaledRegions.mapIndexed { i, region ->
                if (translations[i].isNullOrBlank()) return@mapIndexed region
                val bgColor = TextEraser.sampleBackgroundColor(bitmap, region, pxScale)
                val b = BubbleAreaFinder.expand(bitmap, region, bgColor, pxScale)
                region.copy(
                    corners = listOf(
                        b[0].toFloat() to b[1].toFloat(),
                        b[2].toFloat() to b[1].toFloat(),
                        b[2].toFloat() to b[3].toFloat(),
                        b[0].toFloat() to b[3].toFloat(),
                    )
                )
            }
            TextRenderer.renderTranslations(canvas, regionsForRender, translations, pxScale)
            return writeTranslatedPng(app, bitmap, pageIndex)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 把回填好的整页存成无损 PNG(译图会被原样下载到相册,不能是有损的,也不能是半截)。
     * 看图页单页 / 圈选 / 整部批量共用;编码失败删掉残档并抛出,由调用方按回填失败处理。
     */
    fun writeTranslatedPng(app: Context, bitmap: Bitmap, pageIndex: Int): File {
        val out = File(app.cacheDir, "manga_translated_p${pageIndex}_${System.currentTimeMillis()}.png")
        val ok = try {
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        if (!ok) {
            out.delete()
            error("PNG encode failed: ${bitmap.width}x${bitmap.height}")
        }
        Timber.d("WriteBack: saved %dx%d → %s", bitmap.width, bitmap.height, out.absolutePath)
        return out
    }
}
