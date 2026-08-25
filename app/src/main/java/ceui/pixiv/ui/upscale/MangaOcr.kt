package ceui.pixiv.ui.upscale

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.provider.MediaStore
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.pixiv.ui.translate.ComicTextDetector
import ceui.pixiv.ui.translate.DetectionBox
import ceui.pixiv.ui.translate.MangaTranslateModels
import ceui.pixiv.ui.translate.TextMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * OCR 文本框,**坐标系契约:统一在"原图"分辨率下**。
 * MangaOcr/CTD 内部为防 OOM 会先 sample 降采样,但 [MangaOcr.recognize] 返回前
 * 会把 region 坐标乘回 sample 倍,所以下游(渲染/回填)直接按原图分辨率处理就行。
 */
data class OcrTextRegion(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val orientation: Int, // 0=horizontal, 1=vertical
    val prob: Float, // detection 置信(框是否真是文本框)
    val corners: List<Pair<Float, Float>>,
    val recogConfidence: Float = 1f, // manga-ocr 重识别置信(模型自己对识别多确定)
)

/** 把 region 所有空间字段等比缩放,用于 sample 升/降到目标坐标系。 */
fun OcrTextRegion.scaledBy(factor: Float): OcrTextRegion {
    if (factor == 1f) return this
    return copy(
        cx = cx * factor,
        cy = cy * factor,
        width = width * factor,
        height = height * factor,
        corners = corners.map { (x, y) -> (x * factor) to (y * factor) },
    )
}

/**
 * [MangaOcr.recognize] 的返回值。
 *
 * @property regions 文本 region 列表,坐标系是「原图」(已乘 sample 还原)
 * @property textMask 像素级文本 mask,坐标系是「OCR-sampled bitmap」(即 原图 / [ocrSample])。
 *  调用方按相同 sample 解码自己的 bitmap → mask 自然对齐;dim 不匹配时调用方应该回退到无 mask 路径。
 *  null = 模型未提供 mask 输出。
 * @property ocrSample OCR 时用的 inSampleSize,调用方用相同值解 bitmap 才能跟 mask 对齐
 */
data class MangaOcrResult(
    val regions: List<OcrTextRegion>,
    val textMask: TextMask?,
    val ocrSample: Int,
)

object MangaOcr {

    /**
     * CTD 检测置信下限。CTD 自身已在 [ComicTextDetector] 做了 0.4 阈值 + NMS,
     * 这里再放一道 0.3 当兜底,正常情况下不会再过滤掉东西。
     */
    private const val MIN_DETECTION_PROB = 0.3f

    /**
     * OCR 图片短边目标上限(严格约束:`finalShort <= MAX`)。
     * 2400 兼顾内存(<= ~25MB ARGB_8888)和精度(CTD 内部 letterbox 到 1024)。
     */
    private const val MAX_INPUT_SHORT_SIDE = 2400

    /**
     * region 短边动态门槛:按输入图短边的 0.8% 估算"合理小字",下限 8 px。
     * 1920 → 15.4px, 960 → 7.7px(clamp 8), 4000 → 32px。
     */
    private fun minRegionShortSide(imageShortEdge: Int): Float =
        maxOf(imageShortEdge * 0.008f, 8f)

    /** 省略号 + 句末符号 + 括号引号,不计入"实际字符"。括号常被 manga-ocr 在噪声 crop 上幻读。 */
    private val PUNCTUATION_TO_IGNORE = setOf(
        '.', '…', '。', ',', '、', ' ', '\n', '\t',
        '!', '！', '?', '？', '~', '〜', '・',
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * region 级 manga-ocr 置信度门槛。
     * 真文字典型 0.6-0.95;模型在装饰/反光/小图标上 hallucinate 通常 < 0.3。
     */
    private const val MIN_RECOG_CONFIDENCE = 0.3f

    /**
     * manga-ocr 在真气泡识别完后,常在末尾多吐一个括号字 hallucinate(实测「フォルネウス王子〉」「ヴァネッサ様!?〈」)。
     * 这些字符不会作为合法日文句末出现,**recognize 完直接 trim**。
     * 注意:不能 trim 全部 punctuation,!? 是合法句末。只 trim 真的不该出现的尾巴字符。
     */
    private val TRAILING_NOISE_CHARS = setOf(
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］', '〔', '〕',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * Recognize text in a manga page.
     *
     * Pipeline: comic-text-detector (气泡级 AABB) → manga-ocr (ViT+GPT2) 重识别。
     * 调用前必须把 [models] 加载好([MangaTranslateModels.ensureLoaded]),否则返回 null。
     *
     * Progress 阶段: "检测文本框" 0→0.3,"识别 i/N" 0.3→1.0。
     */
    suspend fun recognize(
        context: Context,
        models: MangaTranslateModels,
        inputFile: File,
        onProgress: ((stage: String, fraction: Float) -> Unit)? = null
    ): MangaOcrResult? = withContext(Dispatchers.IO) {
        // 整段 OCR 是秒级阻塞工作,必须在这里逐段检查取消,否则页面销毁后
        // 气泡检测 + 逐框识别会整段跑完才返回,「工作流无法中断」就出在这一层
        coroutineContext.ensureActive()
        if (!models.ocr.isLoaded) {
            Timber.e("MangaOcr: manga-ocr model not loaded")
            return@withContext null
        }
        if (!models.detector.isLoaded) {
            Timber.e("MangaOcr: comic-text-detector model not loaded")
            return@withContext null
        }
        var bitmap: Bitmap? = null
        try {
            // 先 inJustDecodeBounds 测原图,大图通过 inSampleSize 降采样防 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
            val origShort = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (origShort / sample > MAX_INPUT_SHORT_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("MangaOcr: failed to decode input")
                return@withContext null
            }
            coroutineContext.ensureActive()
            Timber.d("MangaOcr: orig ${bounds.outWidth}x${bounds.outHeight} sample=$sample → ${bitmap!!.width}x${bitmap!!.height}")

            // 检测阶段没有可靠百分比 → 用 NaN 通知 caller 切 indeterminate ring
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_detecting), Float.NaN)

            coroutineContext.ensureActive()
            val detResult = models.detector.detect(bitmap!!)
            coroutineContext.ensureActive()
            val rawRegions = detResult.boxes.map { it.toOcrTextRegion() }
            Timber.d("MangaOcr: CTD returned ${rawRegions.size} regions, mask=${detResult.textMask?.let { "${it.width}x${it.height}" } ?: "null"}")

            // debug 图只在 debug build 走相册落盘 — release 不能给用户相册塞调试 PNG
            if (BuildConfig.DEBUG) {
                saveDetectionDebugImage(context, bitmap!!, rawRegions)
            }

            val minShort = minRegionShortSide(minOf(bitmap!!.width, bitmap!!.height))
            val viableRegions = rawRegions.filter { r ->
                val short = minOf(r.width, r.height)
                r.prob >= MIN_DETECTION_PROB && short >= minShort
            }
            Timber.d(
                "MangaOcr: detection ${rawRegions.size} → viable ${viableRegions.size} " +
                    "(prob>=$MIN_DETECTION_PROB, short>=$minShort)"
            )

            val total = viableRegions.size
            val enhanced = viableRegions.mapIndexedNotNull { idx, region ->
                // 逐 region 检查取消:一页常有几十个气泡,不能让取消憋到全部识别完
                coroutineContext.ensureActive()
                onProgress?.invoke(
                    context.getString(R.string.string_ai_ocr_recognizing, idx + 1, total),
                    idx.toFloat() / total.coerceAtLeast(1)
                )
                var cropped: Bitmap? = null
                try {
                    cropped = aabbCrop(bitmap!!, region)
                    val result = models.ocr.recognize(cropped)
                    val trimmed = result.text.trimEnd { it in TRAILING_NOISE_CHARS }
                    Timber.d(
                        "MangaOcr: → [${result.text}]" +
                            (if (trimmed != result.text) " trimmed→[$trimmed]" else "") +
                            " conf=%.2f".format(result.confidence)
                    )
                    if (trimmed.isBlank()) null
                    else region.copy(text = trimmed, recogConfidence = result.confidence)
                } catch (e: CancellationException) {
                    // 页面销毁取消:必须重抛,不能被当成「该 region 识别失败」吞掉继续跑
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "MangaOcr: manga-ocr failed for region, dropping")
                    null
                } finally {
                    cropped?.recycle()
                }
            }
            coroutineContext.ensureActive()
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_done), 1f)

            val confident = enhanced.filter { it.recogConfidence >= MIN_RECOG_CONFIDENCE }
            Timber.d("MangaOcr: ${enhanced.size} → ${confident.size} after recog-confidence filter (>=$MIN_RECOG_CONFIDENCE)")

            // 坐标系归一:CTD/recognize 是在 sample 后 bitmap 上跑的,这里乘回 sample
            // 把 region 转成"原图分辨率"坐标,下游(渲染/回填)统一基于原图坐标处理。
            val finalRegions = confident
                .filter { it.isMeaningfulJapanese() }
                .let { mangaReadingOrder(it) }
                .map { it.scaledBy(sample.toFloat()) }
            if (sample > 1 && finalRegions.isNotEmpty()) {
                val sample0 = finalRegions[0]
                Timber.d(
                    "MangaOcr: scaled %d regions by x%d → original coords; sample[0] cx=%.0f cy=%.0f w=%.0f h=%.0f",
                    finalRegions.size, sample, sample0.cx, sample0.cy, sample0.width, sample0.height
                )
            }
            MangaOcrResult(regions = finalRegions, textMask = detResult.textMask, ocrSample = sample)
        } catch (e: CancellationException) {
            // 取消不是 OCR 失败:重抛给上层按「翻译取消」处理,别记成 error 日志
            throw e
        } catch (e: Exception) {
            Timber.e(e, "MangaOcr error")
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * 把 CTD 输出转成 [OcrTextRegion]。
     * - text 占位空串,manga-ocr 后续重识别覆盖
     * - orientation 启发式:明显竖长(高 > 宽*1.2)→ 竖排;否则横排
     *   (方形 / 略竖长 一律横排 — 翻译多为中文,横排显示更自然;此前默认竖排导致
     *    短促对白如"やった!"→"做到了!"被强排成单列细窄文字,与气泡框对不齐)
     * - corners 用 AABB 四角(TL→TR→BR→BL),供 TextRenderer / TextEraser 使用
     */
    private fun DetectionBox.toOcrTextRegion(): OcrTextRegion {
        val left = cx - width / 2f
        val top = cy - height / 2f
        val right = cx + width / 2f
        val bottom = cy + height / 2f
        // 0=horizontal(默认 + 略竖长), 1=vertical(明显竖长)
        val orient = if (height > width * 1.2f) 1 else 0
        return OcrTextRegion(
            text = "",
            cx = cx,
            cy = cy,
            width = width,
            height = height,
            angle = 0f,
            orientation = orient,
            prob = confidence,
            corners = listOf(
                left to top, right to top,
                right to bottom, left to bottom,
            ),
        )
    }

    /** AABB crop,带 4% padding(以短边为基准),给 manga-ocr 留点气泡背景。 */
    private fun aabbCrop(bitmap: Bitmap, region: OcrTextRegion): Bitmap {
        val pad = (minOf(region.width, region.height) * 0.04f).toInt().coerceAtLeast(2)
        val left = (region.cx - region.width / 2f - pad).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.cy - region.height / 2f - pad).toInt().coerceIn(0, bitmap.height - 1)
        val right = (region.cx + region.width / 2f + pad).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.cy + region.height / 2f + pad).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /**
     * Debug only: 把检测阶段所有 raw region 的 quad 用纯红填到原图副本上,
     * Q+ 走 MediaStore 落到 Pictures/Pixiv-Shaft-OCR/ 让相册直接看到;
     * <Q fallback externalCacheDir(老机器没用相册的简便办法)。失败不影响主管线。
     */
    private fun saveDetectionDebugImage(
        context: Context,
        source: Bitmap,
        regions: List<OcrTextRegion>,
    ) {
        var debug: Bitmap? = null
        try {
            debug = source.copy(Bitmap.Config.ARGB_8888, true) ?: return
            val canvas = Canvas(debug)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF0000.toInt()
                style = Paint.Style.FILL
            }
            for (r in regions) {
                val pts = r.corners
                if (pts.size < 4) continue
                val path = Path().apply {
                    moveTo(pts[0].first, pts[0].second)
                    for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            val name = "ocr_debug_boxes_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pixiv-Shaft-OCR")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        debug.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    Timber.d("MangaOcr: detection debug image saved → Pictures/Pixiv-Shaft-OCR/$name (${regions.size} boxes)")
                } else {
                    Timber.w("MangaOcr: MediaStore insert returned null, debug image not saved")
                }
            } else {
                val dir = context.externalCacheDir ?: context.cacheDir
                val outFile = File(dir, name)
                FileOutputStream(outFile).use { out ->
                    debug.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Timber.d("MangaOcr: detection debug image saved → ${outFile.absolutePath} (${regions.size} boxes)")
            }
        } catch (e: Exception) {
            Timber.w(e, "MangaOcr: failed to save detection debug image (non-fatal)")
        } finally {
            debug?.recycle()
        }
    }

    /**
     * 合法日文 region 判据:
     *   - 含至少一个日文/汉字字符
     *   - 去掉省略号/括号/标点后,实际字符数 >= 2
     *     或 == 1 且 (detection prob >= 0.85 **且** manga-ocr 自身 conf >= 0.85)
     */
    private fun OcrTextRegion.isMeaningfulJapanese(): Boolean {
        val coreChars = text.count { it !in PUNCTUATION_TO_IGNORE }
        if (coreChars == 0) return false
        val hasJa = text.any { ch ->
            val block = Character.UnicodeBlock.of(ch) ?: return@any false
            block === Character.UnicodeBlock.HIRAGANA ||
                block === Character.UnicodeBlock.KATAKANA ||
                block === Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block === Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block === Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
        if (!hasJa) return false
        return coreChars >= 2 || (prob >= 0.85f && recogConfidence >= 0.85f)
    }

    /**
     * 阅读顺序:cy 升序贪心分 row(下一个 region 的 cy 落在当前 row 累计纵向区间内就并入,
     * 否则开新 row),每个 row 内按 cx 右→左。
     */
    private fun mangaReadingOrder(regions: List<OcrTextRegion>): List<OcrTextRegion> {
        if (regions.size <= 1) return regions
        val byCy = regions.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<OcrTextRegion>>()
        for (r in byCy) {
            val cur = rows.lastOrNull()
            if (cur == null) {
                rows += mutableListOf(r)
                continue
            }
            val rowBottom = cur.maxOf { it.cy + it.height / 2f }
            if (r.cy - r.height / 2f < rowBottom) cur += r
            else rows += mutableListOf(r)
        }
        return rows.flatMap { row -> row.sortedByDescending { it.cx } }
    }
}
