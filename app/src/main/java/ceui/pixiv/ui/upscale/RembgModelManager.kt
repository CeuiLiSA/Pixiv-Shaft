package ceui.pixiv.ui.upscale

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object RembgModelManager {

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun modelDir(context: Context, model: RembgModel): File {
        return File(context.filesDir, "rembg-models/${model.assetDir}")
    }

    fun isModelReady(context: Context, model: RembgModel): Boolean {
        if (model.bundledInApk) return true
        val dir = modelDir(context, model)
        return model.modelFiles.all { File(dir, it).exists() }
    }

    suspend fun downloadModel(
        context: Context,
        model: RembgModel,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val url = model.downloadUrl ?: return@withContext false
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.e("Model download failed: HTTP ${response.code}")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val totalBytes = body.contentLength()

            val tempZip = File(context.cacheDir, "model_dl_${model.assetDir}.zip")
            body.byteStream().use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, n)
                        bytesRead += n
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }

            val dir = modelDir(context, model)
            dir.mkdirs()
            ZipInputStream(tempZip.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val file = File(dir, entry.name)
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            tempZip.delete()

            val ready = model.modelFiles.all { File(dir, it).exists() }
            Timber.d("Model ${model.assetDir} download complete, ready=$ready")
            ready
        } catch (e: Exception) {
            Timber.e(e, "Model download error: ${model.assetDir}")
            false
        }
    }

    fun deleteModel(context: Context, model: RembgModel) {
        val dir = modelDir(context, model)
        if (dir.exists()) dir.deleteRecursively()
    }
}
