package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            bitmap.recycle()

            val pb = ProcessBuilder(
                executablePath,
                "-i", pngInput.absolutePath,
                "-m", modelDir.absolutePath,
                "-g", "0",
                "-s", "960"
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(false) // keep stdout (JSON) and stderr (progress) separate

            val process = pb.start()

            // Read stderr for progress in a background thread
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

            // Read stdout for JSON result
            val jsonOutput = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            stderrThread.join()
            pngInput.delete()

            Timber.d("MangaOcr exit=$exitCode, output=${jsonOutput.take(200)}")

            if (exitCode != 0 || jsonOutput.isBlank()) {
                Timber.e("MangaOcr failed: exit=$exitCode")
                return@withContext null
            }

            // Parse JSON
            val arr = JSONArray(jsonOutput)
            val results = mutableListOf<OcrTextRegion>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cornersArr = obj.getJSONArray("corners")
                val corners = (0 until cornersArr.length()).map { j ->
                    val pt = cornersArr.getJSONArray(j)
                    Pair(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                results.add(OcrTextRegion(
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
            groupRegions(results)
        } catch (e: Exception) {
            Timber.e(e, "MangaOcr error")
            null
        }
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
     * Regions whose bounding boxes are within [gap] pixels are merged.
     * Text within each group is concatenated in spatial reading order.
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

        // Compute axis-aligned bounding box for each region
        data class AABB(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

        val boxes = regions.map { r ->
            val xs = r.corners.map { it.first }
            val ys = r.corners.map { it.second }
            AABB(xs.min(), ys.min(), xs.max(), ys.max())
        }

        // Merge regions whose AABBs overlap or are within gap pixels
        val gap = regions.map { maxOf(it.width, it.height) }.average().toFloat() * 0.5f

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = boxes[i]
                val b = boxes[j]
                val overlapX = a.minX - gap <= b.maxX && b.minX - gap <= a.maxX
                val overlapY = a.minY - gap <= b.maxY && b.minY - gap <= a.maxY
                if (overlapX && overlapY) {
                    union(i, j)
                }
            }
        }

        // Group by root
        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }

        // For each group, sort and concatenate text
        return groups.values.map { indices ->
            // Sort: vertical text right-to-left (by cx desc), horizontal text top-to-bottom (by cy asc)
            val mainOrientation = indices.map { regions[it].orientation }.groupBy { it }
                .maxByOrNull { it.value.size }?.key ?: 0

            val sorted = if (mainOrientation == 1) {
                // Vertical: right column first (reading order is right-to-left)
                indices.sortedByDescending { regions[it].cx }
            } else {
                // Horizontal: top to bottom
                indices.sortedBy { regions[it].cy }
            }

            val combinedText = sorted.joinToString("") { regions[it].text }

            // Use first region's position as representative
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
