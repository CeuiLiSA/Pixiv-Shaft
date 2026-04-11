package ceui.pixiv.ui.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import ceui.pixiv.ui.upscale.MangaOcr
import ceui.pixiv.ui.upscale.OcrTextRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Full manga translation pipeline:
 *   1. OCR — detect text regions
 *   2. Translate — Japanese → Chinese for each region
 *   3. Erase — fill text regions with background color
 *   4. Render — draw translated text into the cleaned regions
 *
 * All processing runs offline on-device.
 */
object MangaTranslator {

    enum class Stage { OCR, TRANSLATE, ERASE, RENDER, DONE }

    data class TranslationResult(
        val outputFile: File,
        val regions: List<OcrTextRegion>,
        val translations: Map<Int, String>
    )

    /**
     * Run the full translation pipeline on a manga page image.
     *
     * @param context Android context
     * @param inputFile Input image file (any format)
     * @param onProgress Callback for stage progress updates
     * @return The translated image file, or null on failure
     */
    suspend fun translate(
        context: Context,
        inputFile: File,
        onProgress: (stage: Stage, detail: String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.IO) {
        try {
            // ── Stage 0: Load manga-ocr model if available ──
            val ocrModel = MangaOcrModel.MANGA_OCR_BASE
            if (MangaOcrModelManager.isModelReady(context, ocrModel) && !MangaOcrRecognizer.isLoaded) {
                onProgress(Stage.OCR, "正在加载 OCR 模型…")
                MangaOcrRecognizer.loadModel(context, ocrModel)
            }

            // ── Stage 1: OCR ──
            onProgress(Stage.OCR, "正在识别文字…")
            val regions = MangaOcr.recognize(context, inputFile)
            if (regions.isNullOrEmpty()) {
                Timber.d("MangaTranslator: no text regions found")
                onProgress(Stage.DONE, "未检测到文字")
                return@withContext null
            }
            Timber.d("MangaTranslator: found ${regions.size} text regions")

            // Log all OCR results
            Timber.d("┌─── OCR Results ───")
            for ((i, r) in regions.withIndex()) {
                Timber.d("│ [%d] text=\"%s\"  pos=(%.0f,%.0f) size=%.0fx%.0f orient=%s prob=%.2f",
                    i, r.text, r.cx, r.cy, r.width, r.height,
                    if (r.orientation == 1) "V" else "H", r.prob)
            }
            Timber.d("└───────────────────")

            coroutineContext.ensureActive()

            // ── Stage 2: Translate each region with Sakura ──
            onProgress(Stage.TRANSLATE, "正在翻译…")

            // Build glossary from detected text (auto-detect common ACG terms)
            val glossary = buildGlossary(regions)
            if (glossary.isNotEmpty()) {
                Timber.d("Glossary: %s", glossary.replace("\n", " | "))
            }

            val inputTexts = regions.map { it.text }
            val batchResults = SakuraTranslator.translateBatch(
                context, inputTexts, glossary.ifEmpty { null }
            ) { done, total ->
                onProgress(Stage.TRANSLATE, "翻译中 ($done/$total)")
            }

            val translations = mutableMapOf<Int, String>()
            Timber.d("┌─── Translation (Sakura-1.5B batch) ───")
            for ((index, region) in regions.withIndex()) {
                val translated = batchResults.getOrNull(index)
                if (translated != null) {
                    translations[index] = translated
                    Timber.d("│ [%d] \"%s\" → \"%s\"", index, region.text, translated)
                } else {
                    translations[index] = region.text
                    Timber.d("│ [%d] \"%s\" → FAILED", index, region.text)
                }
            }
            Timber.d("└────────────────────")

            coroutineContext.ensureActive()

            // ── Stage 3: Erase text ──
            onProgress(Stage.ERASE, "正在擦除原文…")
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val original = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
                ?: return@withContext null

            val erased = TextEraser.eraseText(original, regions)
            original.recycle()

            coroutineContext.ensureActive()

            // ── Stage 4: Render translated text ──
            onProgress(Stage.RENDER, "正在渲染译文…")
            val canvas = Canvas(erased)
            TextRenderer.renderTranslations(canvas, regions, translations)

            // Save output
            val outputFile = File(
                context.cacheDir,
                "manga_translated_${System.currentTimeMillis()}.png"
            )
            FileOutputStream(outputFile).use { out ->
                erased.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            erased.recycle()

            onProgress(Stage.DONE, "翻译完成")
            Timber.d("MangaTranslator: output saved to ${outputFile.absolutePath}")

            TranslationResult(outputFile, regions, translations)
        } catch (e: Exception) {
            Timber.e(e, "MangaTranslator: pipeline error")
            null
        }
    }

    /**
     * Auto-detect common ACG terms in the OCR text and build a glossary.
     * Sakura uses this to maintain consistent character name translations.
     */
    private fun buildGlossary(regions: List<OcrTextRegion>): String {
        val allText = regions.joinToString("") { it.text }

        // Common ACG terms that are frequently mistranslated
        val termMap = linkedMapOf(
            "ウマ娘" to "赛马娘",
            "マスター" to "Master",
            "センパイ" to "前辈",
            "先輩" to "前辈",
            "お兄ちゃん" to "哥哥",
            "お姉ちゃん" to "姐姐",
            "お嬢様" to "大小姐",
            "勇者" to "勇者",
            "魔王" to "魔王",
            "魔法" to "魔法",
            "冒険者" to "冒险者",
            "ギルド" to "公会",
            "ドラゴン" to "龙",
            "エルフ" to "精灵",
            "スライム" to "史莱姆",
            "ダンジョン" to "迷宫",
            "レベル" to "等级",
            "スキル" to "技能",
            "パーティー" to "队伍",
            "クエスト" to "任务",
        )

        val entries = mutableListOf<String>()
        for ((ja, zh) in termMap) {
            if (allText.contains(ja)) {
                entries.add("$ja→$zh")
            }
        }
        return entries.joinToString("\n")
    }
}
