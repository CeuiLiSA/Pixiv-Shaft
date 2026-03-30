package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object NcnnUpscaler {

    private val PROGRESS_REGEX = Regex("""(\d+\.?\d*)%\s*\[\s*[\d.]+s\s*/\s*([\d.]+)\s*ETA""")
    private val TILE_REGEX = Regex("""TILE (\d+)""")

    private fun cacheDir(context: Context): File {
        return File(context.filesDir, "upscale-cache").also { it.mkdirs() }
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getCached(context: Context, inputFile: File, model: UpscaleModel): File? {
        val hash = md5(inputFile)
        val cached = File(cacheDir(context), "${hash}_${model.name}.png")
        return if (cached.exists() && cached.length() > 0) cached else null
    }

    suspend fun upscale(
        context: Context,
        inputFile: File,
        model: UpscaleModel = UpscaleModel.REAL_ESRGAN,
        onProgress: ((percent: Float, etaSeconds: Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val tag = model.displayName

        // Check cache first
        getCached(context, inputFile, model)?.let { cached ->
            Timber.d("$tag: cache hit → ${cached.absolutePath}")
            onProgress?.invoke(1f, 0f)
            return@withContext cached
        }

        try {
            val inputHash = md5(inputFile)
            val modelDir = ensureModelFiles(context, model)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/${model.executableName}"

            // Decode and re-encode as clean PNG
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("$tag: failed to decode input")
                return@withContext null
            }

            val pngInput = File(context.cacheDir, "ncnn_input_${System.currentTimeMillis()}.png")
            FileOutputStream(pngInput).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Timber.d("$tag: input ${bitmap.width}x${bitmap.height} → 2x → ${bitmap.width * 2}x${bitmap.height * 2}")

            // Pre-compute estimated total tile rows for CUGAN tile-counter progress
            // process_se_very_rough: stage0(tile=32,step=3) + stage2(tile=64,step=1)
            // sync_gap is excluded (near-instant, no progress reported)
            val yt32 = (bitmap.height + 31) / 32
            val yt64 = (bitmap.height + 63) / 64
            val estimatedTotalTiles = (yt32 + 2) / 3 + yt64

            bitmap.recycle()

            val outputFile = File(cacheDir(context), "${inputHash}_${model.name}.png")

            val args = mutableListOf(
                executablePath,
                "-i", pngInput.absolutePath,
                "-o", outputFile.absolutePath,
                "-m", modelDir.absolutePath,
                "-t", "64",
                "-g", "0"
            )
            args.addAll(model.extraArgs)

            val pb = ProcessBuilder(args)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(true)

            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                Timber.d("$tag: $line")
                PROGRESS_REGEX.find(line)?.let { match ->
                    val percent = match.groupValues[1].toFloatOrNull() ?: return@let
                    val eta = match.groupValues[2].toFloatOrNull() ?: 0f
                    onProgress?.invoke(percent / 100f, eta)
                    return@forEachLine
                }
                TILE_REGEX.find(line)?.let { match ->
                    val done = match.groupValues[1].toIntOrNull() ?: return@let
                    if (estimatedTotalTiles > 0) {
                        val pct = (done.toFloat() / estimatedTotalTiles).coerceAtMost(0.99f)
                        onProgress?.invoke(pct, 0f)
                    }
                }
            }
            val exitCode = process.waitFor()
            pngInput.delete()

            Timber.d("$tag exit=$exitCode, outputSize=${outputFile.length()}")

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                outputFile
            } else {
                Timber.e("$tag failed: exit=$exitCode")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "$tag upscale error")
            null
        }
    }

    private fun ensureModelFiles(context: Context, model: UpscaleModel): File {
        val modelDir = File(context.filesDir, "realsr-models/${model.extractDir}")
        val firstFile = File(modelDir, model.modelFiles.first())
        if (firstFile.exists()) return modelDir

        modelDir.mkdirs()
        for (name in model.modelFiles) {
            context.assets.open("models/${model.assetDir}/$name").use { input ->
                FileOutputStream(File(modelDir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelDir
    }
}

// Backward compatibility alias
object RealESRGANUpscaler {
    suspend fun upscale(
        context: Context,
        inputFile: File,
        onProgress: ((percent: Float, etaSeconds: Float) -> Unit)? = null
    ): File? = NcnnUpscaler.upscale(context, inputFile, UpscaleModel.REAL_ESRGAN, onProgress)
}
