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

object BackgroundRemover {

    private val PROGRESS_REGEX = Regex("""(\d+\.?\d*)%\s*\[\s*[\d.]+s\s*/\s*([\d.]+)\s*ETA""")

    private fun cacheDir(context: Context): File {
        return File(context.filesDir, "rembg-cache").also { it.mkdirs() }
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

    suspend fun removeBackground(
        context: Context,
        inputFile: File,
        model: RembgModel = RembgModel.U2NETP,
        onProgress: ((percent: Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Cache key includes model name
            val hash = md5(inputFile)
            val cached = File(cacheDir(context), "${hash}_rembg_${model.profileArg}.png")
            if (cached.exists() && cached.length() > 0) {
                Timber.d("Rembg: cache hit [${model.profileArg}] → ${cached.absolutePath}")
                onProgress?.invoke(1f)
                return@withContext cached
            }

            val modelDir = ensureModelFiles(context, model)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/librembg_ncnn.so"

            // Decode and re-encode as clean PNG
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("Rembg: failed to decode input")
                return@withContext null
            }

            val pngInput = File(context.cacheDir, "rembg_input_${System.currentTimeMillis()}.png")
            FileOutputStream(pngInput).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Timber.d("Rembg [${model.profileArg}]: input ${bitmap.width}x${bitmap.height}")
            bitmap.recycle()

            val outputFile = cached

            val pb = ProcessBuilder(
                executablePath,
                "-i", pngInput.absolutePath,
                "-o", outputFile.absolutePath,
                "-m", modelDir.absolutePath,
                "-p", model.profileArg,
                "-g", "0"
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(true)

            val process = pb.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                Timber.d("Rembg: $line")
                PROGRESS_REGEX.find(line)?.let { match ->
                    val percent = match.groupValues[1].toFloatOrNull() ?: return@let
                    onProgress?.invoke(percent / 100f)
                }
            }
            val exitCode = process.waitFor()
            pngInput.delete()

            Timber.d("Rembg exit=$exitCode, outputSize=${outputFile.length()}")

            if (outputFile.exists() && outputFile.length() > 0) {
                if (exitCode != 0) {
                    Timber.w("Rembg exited with code $exitCode but output file is valid, treating as success")
                }
                outputFile
            } else {
                Timber.e("Rembg failed: exit=$exitCode")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Rembg error")
            null
        }
    }

    private fun ensureModelFiles(context: Context, model: RembgModel): File {
        val modelDir = File(context.filesDir, "rembg-models/${model.assetDir}")
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
