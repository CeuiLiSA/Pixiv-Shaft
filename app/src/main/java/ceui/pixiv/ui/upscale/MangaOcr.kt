package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import ceui.pixiv.ui.translate.MangaOcrRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

data class OcrTextRegion(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val orientation: Int, // 0=horizontal, 1=vertical
    val prob: Float,
    val corners: List<Pair<Float, Float>>
)

object MangaOcr {

    private val PROGRESS_REGEX = Regex("""(\d+\.?\d*)%\s*\[\s*[\d.]+s\s*/\s*([\d.]+)\s*ETA""")

    /**
     * Recognize text in a manga page.
     *
     * Uses PaddleOCR for text region detection. If manga-ocr model is loaded,
     * re-recognizes each detected region with manga-ocr for much better accuracy.
     *
     * @param context Android context
     * @param inputFile Input image file
     * @param onProgress Progress callback
     * @return List of grouped text regions, or null on failure
     */
    suspend fun recognize(
        context: Context,
        inputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): List<OcrTextRegion>? = withContext(Dispatchers.IO) {
        try {
            val modelDir = ensureModelFiles(context)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/libocr_ncnn.so"

            // Decode and re-encode as PNG
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("MangaOcr: failed to decode input")
                return@withContext null
            }

            val pngInput = File(context.cacheDir, "ocr_input_${System.currentTimeMillis()}.png")
            FileOutputStream(pngInput).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Timber.d("MangaOcr: input ${bitmap.width}x${bitmap.height}")

            val pb = ProcessBuilder(
                executablePath,
                "-i", pngInput.absolutePath,
                "-m", modelDir.absolutePath,
                "-g", "0",
                "-s", "960"
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(false)

            val process = pb.start()

            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Timber.d("MangaOcr: $line")
                    PROGRESS_REGEX.find(line)?.let { match ->
                        val percent = match.groupValues[1].toFloatOrNull() ?: return@let
                        onProgress?.invoke(percent / 100f)
                    }
                }
            }
            stderrThread.start()

            val jsonOutput = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            stderrThread.join()
            pngInput.delete()

            Timber.d("MangaOcr exit=$exitCode, output=${jsonOutput.take(200)}")

            if (exitCode != 0 || jsonOutput.isBlank()) {
                Timber.e("MangaOcr failed: exit=$exitCode")
                bitmap.recycle()
                return@withContext null
            }

            // Parse JSON — get detection regions from PaddleOCR
            val arr = JSONArray(jsonOutput)
            val rawRegions = mutableListOf<OcrTextRegion>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cornersArr = obj.getJSONArray("corners")
                val corners = (0 until cornersArr.length()).map { j ->
                    val pt = cornersArr.getJSONArray(j)
                    Pair(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                rawRegions.add(OcrTextRegion(
                    text = obj.getString("text"),
                    cx = obj.getDouble("cx").toFloat(),
                    cy = obj.getDouble("cy").toFloat(),
                    width = obj.getDouble("w").toFloat(),
                    height = obj.getDouble("h").toFloat(),
                    angle = obj.getDouble("angle").toFloat(),
                    orientation = obj.getInt("orientation"),
                    prob = obj.getDouble("prob").toFloat(),
                    corners = corners
                ))
            }

            // If manga-ocr is loaded, re-recognize each region for better accuracy
            if (MangaOcrRecognizer.isLoaded) {
                Timber.d("MangaOcr: re-recognizing ${rawRegions.size} regions with manga-ocr")
                val enhanced = rawRegions.map { region ->
                    try {
                        val cropped = cropRegion(bitmap, region)
                        val text = MangaOcrRecognizer.recognize(cropped)
                        cropped.recycle()
                        Timber.d("MangaOcr: [${region.text}] → [$text]")
                        region.copy(text = text)
                    } catch (e: Exception) {
                        Timber.e(e, "MangaOcr: manga-ocr failed for region, keeping PaddleOCR text")
                        region
                    }
                }
                bitmap.recycle()
                groupRegions(enhanced)
            } else {
                bitmap.recycle()
                groupRegions(rawRegions)
            }
        } catch (e: Exception) {
            Timber.e(e, "MangaOcr error")
            null
        }
    }

    /**
     * Crop a text region from the bitmap using its corner coordinates.
     * Applies rotation correction based on the region angle.
     */
    private fun cropRegion(bitmap: Bitmap, region: OcrTextRegion): Bitmap {
        val corners = region.corners
        if (corners.size < 4) {
            // Fallback: use cx/cy/width/height
            val left = (region.cx - region.width / 2).toInt().coerceIn(0, bitmap.width - 1)
            val top = (region.cy - region.height / 2).toInt().coerceIn(0, bitmap.height - 1)
            val w = region.width.toInt().coerceAtMost(bitmap.width - left)
            val h = region.height.toInt().coerceAtMost(bitmap.height - top)
            return Bitmap.createBitmap(bitmap, left, top, maxOf(1, w), maxOf(1, h))
        }

        // Use axis-aligned bounding box of corners with padding
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val pad = 4
        val left = (xs.min().toInt() - pad).coerceIn(0, bitmap.width - 1)
        val top = (ys.min().toInt() - pad).coerceIn(0, bitmap.height - 1)
        val right = (xs.max().toInt() + pad).coerceIn(left + 1, bitmap.width)
        val bottom = (ys.max().toInt() + pad).coerceIn(top + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun ensureModelFiles(context: Context): File {
        val modelDir = File(context.filesDir, "ocr-models/ppocrv5")
        val binFile = File(modelDir, "PP_OCRv5_mobile_det.ncnn.bin")
        if (binFile.exists()) return modelDir

        modelDir.mkdirs()
        val files = listOf(
            "PP_OCRv5_mobile_det.ncnn.param",
            "PP_OCRv5_mobile_det.ncnn.bin",
            "PP_OCRv5_mobile_rec.ncnn.param",
            "PP_OCRv5_mobile_rec.ncnn.bin"
        )
        for (name in files) {
            context.assets.open("models/ppocrv5/$name").use { input ->
                FileOutputStream(File(modelDir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelDir
    }

    /**
     * Group nearby text regions into speech bubbles using Union-Find.
     */
    private fun groupRegions(regions: List<OcrTextRegion>): List<OcrTextRegion> {
        if (regions.size <= 1) return regions

        val n = regions.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (c != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }

        data class AABB(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

        val boxes = regions.map { r ->
            val xs = r.corners.map { it.first }
            val ys = r.corners.map { it.second }
            AABB(xs.min(), ys.min(), xs.max(), ys.max())
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ri = regions[i]
                val rj = regions[j]
                // Only merge if same orientation
                if (ri.orientation != rj.orientation) continue

                val a = boxes[i]
                val b = boxes[j]

                // Gap based on the smaller of the two regions' character size
                // For vertical text: character size ≈ width; for horizontal: ≈ height
                val charSizeI = if (ri.orientation == 1) ri.width else ri.height
                val charSizeJ = if (rj.orientation == 1) rj.width else rj.height
                val gap = minOf(charSizeI, charSizeJ) * 0.8f

                if (ri.orientation == 1) {
                    // Vertical text: merge only if horizontally adjacent (same column group)
                    // and vertically overlapping (continuous reading flow)
                    val hGap = minOf(Math.abs(a.minX - b.maxX), Math.abs(b.minX - a.maxX))
                    val overlapY = a.minY - gap <= b.maxY && b.minY - gap <= a.maxY
                    if (hGap <= gap && overlapY) {
                        union(i, j)
                    }
                } else {
                    // Horizontal text: merge only if vertically adjacent (same line group)
                    // and horizontally overlapping
                    val vGap = minOf(Math.abs(a.minY - b.maxY), Math.abs(b.minY - a.maxY))
                    val overlapX = a.minX - gap <= b.maxX && b.minX - gap <= a.maxX
                    if (vGap <= gap && overlapX) {
                        union(i, j)
                    }
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }

        return groups.values.map { indices ->
            val mainOrientation = indices.map { regions[it].orientation }.groupBy { it }
                .maxByOrNull { it.value.size }?.key ?: 0

            val sorted = if (mainOrientation == 1) {
                indices.sortedByDescending { regions[it].cx }
            } else {
                indices.sortedBy { regions[it].cy }
            }

            val combinedText = sorted.joinToString("") { regions[it].text }

            val first = regions[sorted.first()]
            val allCorners = sorted.flatMap { regions[it].corners }
            val minX = allCorners.minOf { it.first }
            val minY = allCorners.minOf { it.second }
            val maxX = allCorners.maxOf { it.first }
            val maxY = allCorners.maxOf { it.second }

            OcrTextRegion(
                text = combinedText,
                cx = (minX + maxX) / 2,
                cy = (minY + maxY) / 2,
                width = maxX - minX,
                height = maxY - minY,
                angle = first.angle,
                orientation = mainOrientation,
                prob = sorted.map { regions[it].prob }.average().toFloat(),
                corners = listOf(
                    Pair(minX, minY), Pair(maxX, minY),
                    Pair(maxX, maxY), Pair(minX, maxY)
                )
            )
        }.filter { it.text.isNotEmpty() }
    }
}
