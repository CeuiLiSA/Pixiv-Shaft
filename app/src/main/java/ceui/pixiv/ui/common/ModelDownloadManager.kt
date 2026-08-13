package ceui.pixiv.ui.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

abstract class ModelDownloadManager {

    protected abstract val storageSubDir: String
    protected abstract val logTag: String
    protected open val readTimeoutSeconds: Long = 60L

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    fun modelDir(context: Context, model: DownloadableModel): File {
        return File(context.filesDir, "$storageSubDir/${model.assetDir}")
    }

    open fun isModelReady(context: Context, model: DownloadableModel): Boolean {
        val dir = modelDir(context, model)
        return model.modelFiles.all { File(dir, it).exists() }
    }

    suspend fun downloadModel(
        context: Context,
        model: DownloadableModel,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val url = model.downloadUrl ?: return@withContext false
        val tempZip = File(context.cacheDir, "model_dl_${model.assetDir}.zip")
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.e("$logTag download failed: HTTP ${response.code}")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val totalBytes = body.contentLength()

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

            // 下载与导入共用同一套校验安装流程，保证两边行为一致
            installZip(context, model, tempZip)
        } catch (e: Exception) {
            Timber.e(e, "$logTag download error: ${model.assetDir}")
            false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * 导入本地已下载好的模型包（zip）。
     *
     * 流式拷贝到 cache 目录后复用 [installZip]：staging 解压 → 校验 modelFiles 全部存在 →
     * 整目录换入。校验规则与现网下载逻辑一致：必需文件齐全即视为正确，多余文件不判错；
     * 解压中途失败不会留下「文件 exists 但内容残缺」的半成品让 isModelReady 误判 ready。
     */
    suspend fun importModel(
        context: Context,
        model: DownloadableModel,
        uri: Uri,
    ): Boolean = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "model_import_${model.assetDir}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            var importedBytes = 0L
            input.use { stream ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var n: Int
                    while (stream.read(buffer).also { n = it } != -1) {
                        importedBytes += n
                        if (importedBytes > MAX_IMPORT_FILE_BYTES) {
                            throw IllegalArgumentException("model zip too large: $importedBytes bytes")
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (importedBytes == 0L) {
                Timber.w("$logTag ${model.assetDir} import failed: empty file")
                return@withContext false
            }
            installZip(context, model, tempZip)
        } catch (e: Exception) {
            Timber.e(e, "$logTag import error: ${model.assetDir}")
            false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * 校验并安装模型 zip（下载/导入共用）。
     * 先快速校验 zip 魔数，再解压到 staging 目录，成功后整目录换入。
     */
    private fun installZip(context: Context, model: DownloadableModel, tempZip: File): Boolean {
        val magic = ByteArray(4)
        if (tempZip.length() < 4) {
            Timber.w("$logTag ${model.assetDir} too small to be a zip")
            return false
        }
        RandomAccessFile(tempZip, "r").use { raf ->
            raf.readFully(magic)
        }
        if (!magic.contentEquals(ZIP_MAGIC)) {
            Timber.w("$logTag ${model.assetDir} not a valid zip")
            return false
        }

        val dir = modelDir(context, model)
        val staging = File(dir.parentFile, dir.name + ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(tempZip.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val file = File(staging, entry.name)
                    // zip-slip 防护：条目名可能带 ../，canonical 路径必须落在目标目录内
                    if (!file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw SecurityException("zip entry escapes target dir: ${entry.name}")
                    }
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) {
            staging.deleteRecursively()
            throw IllegalStateException("rename ${staging.name} -> ${dir.name} failed")
        }

        val ready = model.modelFiles.all { File(dir, it).exists() }
        Timber.d("$logTag ${model.assetDir} install complete, ready=$ready")
        return ready
    }

    fun deleteModel(context: Context, model: DownloadableModel) {
        val dir = modelDir(context, model)
        if (dir.exists()) dir.deleteRecursively()
    }

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        // 模型包最大不超过 100MB，512MB 上限只用于拦截误选超大文件，避免撑爆 cache
        private const val MAX_IMPORT_FILE_BYTES = 512L * 1024 * 1024
    }
}
