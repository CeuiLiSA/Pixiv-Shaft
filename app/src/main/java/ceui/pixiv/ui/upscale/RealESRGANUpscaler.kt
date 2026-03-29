package ceui.pixiv.ui.upscale

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object RealESRGANUpscaler {

    private const val MODEL_DIR = "Real-ESRGAN-anime"
    private const val MODEL_NAME = "x4"

    suspend fun upscale(context: Context, inputFile: File): File? = withContext(Dispatchers.IO) {
        try {
            val modelDir = ensureModelFiles(context)
            val executablePath = context.applicationInfo.nativeLibraryDir + "/librealsr_ncnn.so"
            val outputFile = File(context.cacheDir, "upscaled_${System.currentTimeMillis()}.png")

            val env = arrayOf(
                "LD_LIBRARY_PATH=${context.applicationInfo.nativeLibraryDir}"
            )

            val command = arrayOf(
                executablePath,
                "-i", inputFile.absolutePath,
                "-o", outputFile.absolutePath,
                "-m", modelDir.absolutePath,
                "-n", MODEL_NAME,
                "-s", "4",
                "-t", "128",
                "-g", "0"
            )

            Timber.d("RealESRGAN command: ${command.joinToString(" ")}")

            val process = Runtime.getRuntime().exec(command, env)
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Timber.d("RealESRGAN exit=$exitCode, stderr=$stderr")

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                outputFile
            } else {
                Timber.e("RealESRGAN failed: exit=$exitCode, stderr=$stderr")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "RealESRGAN upscale error")
            null
        }
    }

    private fun ensureModelFiles(context: Context): File {
        val modelDir = File(context.filesDir, "realsr-models/$MODEL_DIR")
        val binFile = File(modelDir, "$MODEL_NAME.bin")
        if (binFile.exists()) return modelDir

        modelDir.mkdirs()
        for (name in listOf("$MODEL_NAME.bin", "$MODEL_NAME.param")) {
            context.assets.open("models/$MODEL_DIR/$name").use { input ->
                FileOutputStream(File(modelDir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelDir
    }
}
