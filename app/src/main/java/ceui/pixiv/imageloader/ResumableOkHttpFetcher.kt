package ceui.pixiv.imageloader

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.http.ImageHostManager
import ceui.lisa.http.PixivHeaders
import ceui.lisa.utils.Params
import ceui.pixiv.cache.OriginalImageCacheManager
import ceui.pixiv.download.StageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 原图断点续传加载器：基于原生 OkHttp 支持 Range 请求头与 .part 临时文件追加写入，
 * 完成后原子提交至原图专属缓存目录。
 */
class ResumableOkHttpFetcher(
    private val clientProvider: () -> OkHttpClient = { (Shaft.getContext() as Shaft).okHttpClient },
    private val contextProvider: () -> Context = { Shaft.getContext() },
    private val cacheDirProvider: () -> File = { OriginalImageCacheManager.getCacheDir(contextProvider()) },
    private val stagingDirProvider: () -> File = { OriginalImageCacheManager.getStagingDir(contextProvider()) },
) : ImageFetcher {

    companion object : ImageFetcher {
        private val defaultInstance = ResumableOkHttpFetcher()

        override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File =
            defaultInstance.fetch(url, onProgress)

        override suspend fun fetchWithProgress(url: String, onProgress: (ImageFetcher.Progress) -> Unit): File =
            defaultInstance.fetchWithProgress(url, onProgress)
    }

    override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File =
        fetchWithProgress(url) { onProgress(it.percent) }

    // 调用方是 ImageTaskRegistry.scope（Main.immediate）：从查缓存、读 .part / manifest 起就全在 IO 上做。
    override suspend fun fetchWithProgress(url: String, onProgress: (ImageFetcher.Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val cacheDir = cacheDirProvider()
        val cached = OriginalImageCacheManager.find(cacheDir, url)
        if (cached != null) {
            Timber.d("[ResumableFetcher] cache hit url=${url.substringAfterLast('/')}")
            onProgress(ImageFetcher.Progress(99, cached.length(), cached.length()))
            return@withContext cached
        }

        val stagingDir = stagingDirProvider()
        if (!stagingDir.exists()) {
            stagingDir.mkdirs()
        }

        val key = StageStore.keyForUrl(url)
        val partFile = StageStore.partFile(stagingDir, key)
        val metaFile = StageStore.metaFile(stagingDir, key)

        var existingLen = if (partFile.isFile) partFile.length() else 0L
        var manifest = if (existingLen > 0) StageStore.readManifest(metaFile) else null

        if (manifest != null && manifest.total > 0 && existingLen > manifest.total) {
            Timber.w("[ResumableFetcher] stage overshoot existing=$existingLen total=${manifest.total}, reset")
            StageStore.clear(stagingDir, key)
            existingLen = 0L
            manifest = null
        }

        val networkUrl = ImageHostManager.rewrite(url)
        val pixivHeaders = PixivHeaders()

        val reqBuilder = Request.Builder()
            .url(networkUrl)
            .header(Params.MAP_KEY_SMALL, Params.IMAGE_REFERER)
            .header(Params.USER_AGENT, Params.PHONE_MODEL)
            .header("x-client-time", pixivHeaders.xClientTime)
            .header("x-client-hash", pixivHeaders.xClientHash)

        if (existingLen > 0) {
            reqBuilder.header("Range", "bytes=$existingLen-")
            if (manifest != null && manifest.validator != null &&
                (manifest.validatorType == StageStore.VALIDATOR_ETAG ||
                        manifest.validatorType == StageStore.VALIDATOR_LASTMOD)
            ) {
                reqBuilder.header("If-Range", manifest.validator)
            }
        }

        val request = reqBuilder.build()
        val client = clientProvider()

        val call = client.newCall(request)
        var response: Response? = null
        try {
            response = runInterruptible { call.execute() }
            val code = response.code
            if (!response.isSuccessful && code != 416) {
                throw IOException("HTTP $code ${response.message}")
            }

            val body = response.body
            val contentLength = body?.contentLength() ?: -1L
            val contentRange = response.header("Content-Range")

            val decision = StageStore.decideWrite(code, contentRange, contentLength, existingLen)
            Timber.d(
                "[ResumableFetcher] url=${url.substringAfterLast('/')} code=$code mode=${decision.mode} start=${decision.startOffset} total=${decision.total}"
            )

            when (decision.mode) {
                StageStore.WriteMode.ABORT -> {
                    StageStore.clear(stagingDir, key)
                    throw IOException("Resume aborted (code=$code), restart fresh")
                }

                StageStore.WriteMode.ALREADY_COMPLETE -> {
                    onProgress(ImageFetcher.Progress(99, existingLen, decision.total))
                    response.close()

                    val integrity = ceui.pixiv.download.ImageIntegrityValidator.validateAndRepair(partFile)
                    if (!integrity.isValid) {
                        StageStore.clear(stagingDir, key)
                        throw IOException("Image integrity check failed: ${integrity.reason}")
                    }
                    if (integrity.repaired) {
                        Timber.tag("ImageIntegrity").i("[ResumableFetcher] ${integrity.reason}")
                    }

                    OriginalImageCacheManager.commit(cacheDir, stagingDir, url, partFile)
                }

                StageStore.WriteMode.FRESH, StageStore.WriteMode.APPEND -> {
                    val responseBody = body ?: throw IOException("Empty response body")
                    StageStore.writeManifest(
                        metaFile,
                        StageStore.buildManifest(
                            url,
                            response.header("ETag"),
                            response.header("Last-Modified"),
                            decision.total
                        )
                    )

                    val append = decision.mode == StageStore.WriteMode.APPEND
                    var written = if (append) decision.startOffset else 0L
                    val total = decision.total
                    var lastReportedPercent = -1
                    var lastReportedBytes = written

                    lastReportedPercent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 99) else 0
                    onProgress(ImageFetcher.Progress(lastReportedPercent, written, total))

                    val inputStream = responseBody.byteStream()
                    val outputStream = FileOutputStream(partFile, append)

                    try {
                        runInterruptible {
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                written += bytesRead
                                val percent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 99) else 0
                                if (percent != lastReportedPercent || written - lastReportedBytes >= 64 * 1024) {
                                    lastReportedPercent = percent
                                    lastReportedBytes = written
                                    onProgress(ImageFetcher.Progress(percent, written, total))
                                }
                            }
                            outputStream.flush()
                        }
                        if (written != lastReportedBytes) {
                            onProgress(ImageFetcher.Progress(lastReportedPercent, written, total))
                        }
                    } finally {
                        runCatching { outputStream.close() }
                        runCatching { inputStream.close() }
                    }

                    if (decision.total >= 0) {
                        val stagedLen = partFile.length()
                        if (stagedLen != decision.total) {
                            StageStore.clear(stagingDir, key)
                            throw IOException("Stage length mismatch: stagedLen=$stagedLen != expected=${decision.total}")
                        }
                    }

                    val integrity = ceui.pixiv.download.ImageIntegrityValidator.validateAndRepair(partFile)
                    if (!integrity.isValid) {
                        StageStore.clear(stagingDir, key)
                        throw IOException("Image integrity check failed: ${integrity.reason}")
                    }
                    if (integrity.repaired) {
                        Timber.tag("ImageIntegrity").i("[ResumableFetcher] ${integrity.reason}")
                    }

                    OriginalImageCacheManager.commit(cacheDir, stagingDir, url, partFile)
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } finally {
            response?.close()
        }
    }
}
