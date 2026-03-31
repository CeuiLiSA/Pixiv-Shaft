package ceui.pixiv.ui.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Sakura-1.5B translator — ACG-specialized Japanese→Chinese translation.
 *
 * Batch mode: loads model once, translates all lines, outputs one per line.
 */
object SakuraTranslator {

    /**
     * Translate multiple Japanese texts to Chinese in one batch.
     * Model is loaded once for all texts — much faster than per-text calls.
     *
     * @param context Android context
     * @param texts List of Japanese texts to translate
     * @param glossary Optional glossary string
     * @param onProgress Callback with (completedCount, totalCount)
     * @return List of translated texts (same order as input), null entries on failure
     */
    suspend fun translateBatch(
        context: Context,
        texts: List<String>,
        glossary: String? = null,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<String?> = withContext(Dispatchers.IO) {
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/libsakura_translate.so"

            val model = SakuraModel.SAKURA_1_5B
            val modelDir = SakuraModelManager.modelDir(context, model)
            val modelFile = File(modelDir, "sakura-1.5b-q3_k_m.gguf")

            if (!modelFile.exists()) {
                Timber.e("SakuraTranslator: model not found")
                return@withContext texts.map { null }
            }

            // Write input texts to temp file (one per line)
            val inputFile = File(context.cacheDir, "sakura_input_${System.currentTimeMillis()}.txt")
            inputFile.writeText(texts.joinToString("\n"))

            val args = mutableListOf(
                executablePath,
                "-m", modelFile.absolutePath,
                "-f", inputFile.absolutePath,
                "-n", "256",
                "-j", "4"
            )
            if (!glossary.isNullOrBlank()) {
                args.addAll(listOf("-g", glossary))
            }

            val pb = ProcessBuilder(args)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(false)

            val process = pb.start()

            // Read stderr for progress
            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Timber.d("Sakura: %s", line)
                    // Parse "Translating [3/8]..."
                    val match = Regex("""Translating \[(\d+)/(\d+)]""").find(line)
                    if (match != null) {
                        val done = match.groupValues[1].toIntOrNull() ?: 0
                        val total = match.groupValues[2].toIntOrNull() ?: texts.size
                        onProgress?.invoke(done, total)
                    }
                }
            }
            stderrThread.start()

            // Read stdout — one translated line per input line
            val resultLines = process.inputStream.bufferedReader().readLines()
            val exitCode = process.waitFor()
            stderrThread.join()
            inputFile.delete()

            if (exitCode != 0) {
                Timber.e("SakuraTranslator: exit code $exitCode")
                return@withContext texts.map { null }
            }

            // Map results back
            texts.indices.map { i ->
                resultLines.getOrNull(i)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Timber.e(e, "SakuraTranslator: error")
            texts.map { null }
        }
    }
}
