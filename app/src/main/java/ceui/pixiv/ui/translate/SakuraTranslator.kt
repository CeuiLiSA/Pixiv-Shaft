package ceui.pixiv.ui.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Sakura-1.5B translator — ACG-specialized Japanese→Chinese translation.
 *
 * 支持长文本（小说级别）：
 * 1. 长段落按句号自动分句
 * 2. 分批调用进程（每批 BATCH_SIZE 个 chunk），避免内存爆掉
 * 3. 单批失败不影响其他批次，已翻译的结果保留
 */
object SakuraTranslator {

    private const val MAX_CHARS_PER_LINE = 200
    private const val BATCH_SIZE = 20
    private const val MAX_TOKENS = 512

    suspend fun translateBatch(
        context: Context,
        texts: List<String>,
        glossary: String? = null,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<String?> = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val executablePath = "$nativeDir/libsakura_translate.so"

        val model = SakuraModel.SAKURA_1_5B
        val modelDir = SakuraModelManager.modelDir(context, model)
        val modelFile = File(modelDir, "sakura-1.5b-q3_k_m.gguf")

        if (!modelFile.exists()) {
            Timber.e("SakuraTranslator: model not found at ${modelFile.absolutePath}")
            return@withContext texts.map { null }
        }

        // ====== 1. 分句：长段落按句号拆开 ======
        val chunks = mutableListOf<String>()
        val chunkOriginIndex = mutableListOf<Int>()
        for ((i, text) in texts.withIndex()) {
            if (text.length <= MAX_CHARS_PER_LINE) {
                chunks.add(text)
                chunkOriginIndex.add(i)
            } else {
                val sentences = text.split(Regex("(?<=。)|(?<=！)|(?<=？)|(?<=」)"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                for (s in sentences) {
                    chunks.add(s)
                    chunkOriginIndex.add(i)
                }
            }
        }
        val totalChunks = chunks.size
        Timber.d("SakuraTranslator: ${texts.size} texts -> $totalChunks chunks, batch=$BATCH_SIZE")

        // ====== 2. 分批翻译 ======
        val allResults = arrayOfNulls<String>(totalChunks)
        var completedChunks = 0

        val batches = chunks.chunked(BATCH_SIZE)
        Timber.d("SakuraTranslator: ${batches.size} batches to process")

        for ((batchIdx, batch) in batches.withIndex()) {
            val batchStart = batchIdx * BATCH_SIZE
            Timber.d("SakuraTranslator: batch ${batchIdx + 1}/${batches.size}, chunks ${batchStart + 1}-${batchStart + batch.size}/$totalChunks")

            try {
                val batchResults = runSakuraProcess(
                    executablePath, modelFile, nativeDir, batch, glossary
                ) { done, _ ->
                    onProgress?.invoke(completedChunks + done, totalChunks)
                }

                for ((j, result) in batchResults.withIndex()) {
                    allResults[batchStart + j] = result
                }
                completedChunks += batch.size
                onProgress?.invoke(completedChunks, totalChunks)
                Timber.d("SakuraTranslator: batch ${batchIdx + 1} done, ${batchResults.count { it != null }}/${batch.size} succeeded")
            } catch (e: Exception) {
                Timber.e(e, "SakuraTranslator: batch ${batchIdx + 1} failed, skipping")
                completedChunks += batch.size
                onProgress?.invoke(completedChunks, totalChunks)
            }
        }

        // ====== 3. 合并分句结果回原始段落 ======
        val merged = Array<StringBuilder?>(texts.size) { null }
        for (ci in chunks.indices) {
            val origIdx = chunkOriginIndex[ci]
            val translated = allResults[ci]
            if (translated != null) {
                if (merged[origIdx] == null) {
                    merged[origIdx] = StringBuilder(translated)
                } else {
                    merged[origIdx]!!.append(translated)
                }
            }
        }

        val finalResults = texts.indices.map { merged[it]?.toString() }
        val succeeded = finalResults.count { it != null }
        Timber.d("SakuraTranslator: all done, $succeeded/${texts.size} texts translated")
        finalResults
    }

    /**
     * 单次进程调用，翻译一个 batch 的 chunks。
     */
    private fun runSakuraProcess(
        executablePath: String,
        modelFile: File,
        nativeDir: String,
        lines: List<String>,
        glossary: String?,
        onBatchProgress: ((Int, Int) -> Unit)? = null
    ): List<String?> {
        val inputFile = File(modelFile.parentFile, "sakura_input_${System.currentTimeMillis()}.txt")
        try {
            inputFile.writeText(lines.joinToString("\n"))

            val args = mutableListOf(
                executablePath,
                "-m", modelFile.absolutePath,
                "-f", inputFile.absolutePath,
                "-n", MAX_TOKENS.toString(),
                "-j", "4"
            )
            if (!glossary.isNullOrBlank()) {
                args.addAll(listOf("-g", glossary))
            }

            val pb = ProcessBuilder(args)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(false)

            val process = pb.start()

            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Timber.d("Sakura: %s", line)
                    val match = Regex("""Translating \[(\d+)/(\d+)]""").find(line)
                    if (match != null) {
                        val done = match.groupValues[1].toIntOrNull() ?: 0
                        val total = match.groupValues[2].toIntOrNull() ?: lines.size
                        onBatchProgress?.invoke(done, total)
                    }
                }
            }
            stderrThread.start()

            val resultLines = process.inputStream.bufferedReader().readLines()
            val exitCode = process.waitFor()
            stderrThread.join()

            if (exitCode != 0) {
                Timber.e("SakuraTranslator: process exit code $exitCode")
                return lines.map { null }
            }

            return lines.indices.map { i ->
                resultLines.getOrNull(i)?.takeIf { it.isNotBlank() }
            }
        } finally {
            inputFile.delete()
        }
    }
}
