package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object RealESRGANUpscaler {

    private const val MODEL_ASSET_DIR = "Real-ESRGANv3-anime"
    private const val MODEL_EXTRACT_DIR = "models-Real-ESRGANv3-anime"
    private const val MODEL_NAME = "x2"
    private val PROGRESS_REGEX = Regex("""(\d+\.?\d*)%\s*\[\s*[\d.]+s\s*/\s*([\d.]+)\s*ETA""")

    /**
     * Upscale using the best anime 4x model.
     * To achieve effective 2x: downscale input by half first, then run 4x model.
     * This gives best quality at 2x with ~4x faster speed than full 4x.
     */
    suspend fun upscale(
        context: Context,
        inputFile: File,
        onProgress: ((percent: Float, etaSeconds: Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val modelDir = ensureModelFiles(context)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/librealsr_ncnn.so"

            // Decode and re-encode as clean PNG
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("RealESRGAN: failed to decode input")
                return@withContext null
            }

            val pngInput = File(context.cacheDir, "realsr_input_${System.currentTimeMillis()}.png")
            FileOutputStream(pngInput).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Timber.d("RealESRGAN: input ${bitmap.width}x${bitmap.height} → 2x → ${bitmap.width * 2}x${bitmap.height * 2}")
            bitmap.recycle()

            val outputFile = File(context.cacheDir, "upscaled_${System.currentTimeMillis()}.png")

            val pb = ProcessBuilder(
                executablePath,
                "-i", pngInput.absolutePath,
                "-o", outputFile.absolutePath,
                "-m", modelDir.absolutePath,
                "-t", "64",
                "-g", "0"
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(true)

            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                Timber.d("RealESRGAN: $line")
                PROGRESS_REGEX.find(line)?.let { match ->
                    val percent = match.groupValues[1].toFloatOrNull() ?: return@let
                    val eta = match.groupValues[2].toFloatOrNull() ?: 0f
                    onProgress?.invoke(percent / 100f, eta)
                }
            }
            val exitCode = process.waitFor()
            pngInput.delete()

            Timber.d("RealESRGAN exit=$exitCode, outputSize=${outputFile.length()}")

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                outputFile
            } else {
                Timber.e("RealESRGAN failed: exit=$exitCode")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "RealESRGAN upscale error")
            null
        }
    }

    private fun ensureModelFiles(context: Context): File {
        val modelDir = File(context.filesDir, "realsr-models/$MODEL_EXTRACT_DIR")
        val binFile = File(modelDir, "$MODEL_NAME.bin")
        if (binFile.exists()) return modelDir

        modelDir.mkdirs()
        for (name in listOf("$MODEL_NAME.bin", "$MODEL_NAME.param")) {
            context.assets.open("models/$MODEL_ASSET_DIR/$name").use { input ->
                FileOutputStream(File(modelDir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelDir
    }
}
