package ceui.pixiv.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ceui.lisa.http.GithubProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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
        val ready = ModelArchiveInstaller.recoverReady(dir, model.modelFiles)
        val cacheDir = context.cacheDir
        val backup = File(dir.parentFile, dir.name + ".backup")
        if (ModelArchiveInstaller.hasTempArtifacts(cacheDir, dir, model.assetDir) ||
            (!ready && dir.exists()) || backup.exists()
        ) {
            cleanupScope.launch {
                withModelLock(dir) {
                    ModelArchiveInstaller.cleanResidue(cacheDir, dir, model.assetDir, model.modelFiles)
                }
            }
        }
        return ready
    }

    suspend fun downloadModel(
        context: Context,
        model: DownloadableModel,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val url = model.downloadUrl ?: return@withContext false
        val dir = modelDir(context, model)
        val job = coroutineContext[Job] ?: error("model download has no coroutine job")
        withModelLock(dir) {
            val tempZip = ModelArchiveInstaller.prepareTempArchive(context.cacheDir, dir, model.assetDir)
            try {
                val downloaded = runInterruptible {
                    val request = Request.Builder().url(GithubProxy.wrap(url)).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.e("$logTag download failed: HTTP ${response.code}")
                            return@use false
                        }
                        val body = response.body ?: return@use false
                        val totalBytes = body.contentLength()
                        body.byteStream().use { input ->
                            FileOutputStream(tempZip).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead = 0L
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    job.ensureActive()
                                    output.write(buffer, 0, n)
                                    bytesRead += n
                                    onProgress(bytesRead, totalBytes)
                                }
                            }
                        }
                        true
                    }
                }
                if (!downloaded) return@withModelLock false
                job.ensureActive()
                installZip(context, model, tempZip) { job.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job.ensureActive()
                Timber.e(e, "$logTag download error: ${model.assetDir}")
                false
            } finally {
                tempZip.delete()
            }
        }
    }

    /**
     * 导入本地已下载好的模型包（zip）。
     * 流式拷贝到 cache 后复用暂存校验与安装流程。
     */
    suspend fun importModel(
        context: Context,
        model: DownloadableModel,
        uri: Uri,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = modelDir(context, model)
        val job = coroutineContext[Job] ?: error("model import has no coroutine job")
        withModelLock(dir) {
            val tempZip = ModelArchiveInstaller.prepareTempArchive(
                context.cacheDir, dir, model.assetDir, importing = true,
            )
            try {
                val importedBytes = runInterruptible {
                    val input = context.contentResolver.openInputStream(uri) ?: return@runInterruptible 0L
                    var bytes = 0L
                    val totalBytes = querySize(context, uri)
                    input.use { stream ->
                        FileOutputStream(tempZip).use { output ->
                            val buffer = ByteArray(8192)
                            var n: Int
                            while (stream.read(buffer).also { n = it } != -1) {
                                job.ensureActive()
                                bytes += n
                                if (bytes > MAX_IMPORT_FILE_BYTES) {
                                    throw IllegalArgumentException("model zip too large: $bytes bytes")
                                }
                                output.write(buffer, 0, n)
                                onProgress(bytes, totalBytes)
                            }
                        }
                    }
                    bytes
                }
                if (importedBytes == 0L) {
                    Timber.w("$logTag ${model.assetDir} import failed: empty file")
                    return@withModelLock false
                }
                job.ensureActive()
                installZip(context, model, tempZip) { job.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job.ensureActive()
                Timber.e(e, "$logTag import error: ${model.assetDir}")
                false
            } finally {
                tempZip.delete()
            }
        }
    }

    /** 从 SAF URI 读取文件大小，拿不到（部分 provider 不支持）返回 -1，UI 走不确定进度。 */
    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use -1L
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
    }.getOrDefault(-1L)

    private fun installZip(
        context: Context,
        model: DownloadableModel,
        tempZip: File,
        checkCancelled: () -> Unit,
    ): Boolean {
        val ready = ModelArchiveInstaller.install(tempZip, modelDir(context, model), model.modelFiles, checkCancelled)
        Timber.d("$logTag ${model.assetDir} install complete, ready=$ready")
        return ready
    }

    suspend fun deleteModel(context: Context, model: DownloadableModel) = withContext(Dispatchers.IO) {
        val dir = modelDir(context, model)
        withModelLock(dir) {
            ModelArchiveInstaller.delete(dir, context.cacheDir, model.assetDir)
        }
    }

    fun requestDeleteModel(context: Context, model: DownloadableModel): Job =
        cleanupScope.launch { deleteModel(context.applicationContext, model) }

    fun requestDeleteModel(context: Context, model: DownloadableModel, onDeleted: Runnable): Job =
        cleanupScope.launch {
            deleteModel(context.applicationContext, model)
            withContext(Dispatchers.Main) { onDeleted.run() }
        }

    fun cancelAndDeleteModel(context: Context, model: DownloadableModel, activeJob: Job): Job {
        activeJob.cancel()
        return cleanupScope.launch {
            activeJob.join()
            deleteModel(context.applicationContext, model)
        }
    }

    private suspend fun <T> withModelLock(dir: File, block: suspend () -> T): T {
        val mutex = synchronized(modelLocks) {
            modelLocks.getOrPut(dir.absolutePath) { Mutex() }
        }
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private val modelLocks = mutableMapOf<String, Mutex>()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 模型包最大不超过 100MB，512MB 上限只用于拦截误选超大文件，避免撑爆 cache
        private const val MAX_IMPORT_FILE_BYTES = 512L * 1024 * 1024
    }
}
