package ceui.pixiv.ui.translate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import android.graphics.Bitmap

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

    /** 回填渲染时图像短边的上限,2400 对齐 [MangaOcr.MAX_INPUT_SHORT_SIDE]。 */
    const val MAX_RENDER_SHORT_SIDE = 2400

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
                renderTranslated(app, imageFile, pageIndex, regions, translations, ocrResult.textMask, ocrResult.ocrSample)
            }.onFailure { Timber.e(it, "renderTranslated failed") }.getOrNull()
        } ?: return Outcome.RenderFailed
        return Outcome.Done(outFile)
    }

    /** 优先展示上游状态；开始出译文后恢复翻译中，不把 reasoning 写入译文。 */
    fun translatePhaseStage(app: Context, phase: AiTranslatePhase): Stage =
        Stage(phase.statusText(app))

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

}
