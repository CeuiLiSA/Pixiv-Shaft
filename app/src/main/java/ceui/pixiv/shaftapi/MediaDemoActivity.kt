package ceui.pixiv.shaftapi

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.api.ClientManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/** Minimal COS direct-upload smoke screen. The Tokyo API never receives image bytes. */
class MediaDemoActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var select: Button
    private lateinit var download: Button
    private var latestMediaId: String? = null
    private var latestDownloadUrl: MediaDownloadUrlResponse? = null
    private val http = OkHttpClient()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri) else MediaUploadTrace("picker").event("cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_demo)
        status = findViewById(R.id.media_demo_status)
        progress = findViewById(R.id.media_demo_progress)
        select = findViewById(R.id.media_demo_select)
        download = findViewById(R.id.media_demo_download)
        select.setOnClickListener {
            MediaUploadTrace("picker").event("open", "type=image/*")
            pickImage.launch("image/*")
        }
        download.setOnClickListener { openDownloadUrl() }
    }

    private fun upload(uri: Uri) {
        val trace = MediaUploadTrace("upload")
        lifecycleScope.launch {
            try {
                trace.stage("metadata", "scheme=${uri.scheme} provider=${uri.authority}")
                val type = contentResolver.getType(uri) ?: run {
                    trace.event("rejected", "reason=unknown_content_type")
                    status.text = "无法识别图片类型"
                    return@launch
                }
                val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                } ?: -1L
                trace.event("result", "contentType=$type size=$size maxBytes=${25L * 1024 * 1024}")
                if (size <= 0L || size > 25L * 1024 * 1024) {
                    trace.event("rejected", "reason=invalid_size")
                    status.text = "图片大小无效（最大 25 MB）"
                    return@launch
                }
                select.isEnabled = false
                download.isEnabled = false
                progress.visibility = ProgressBar.VISIBLE
                progress.progress = 0
                status.text = "正在申请直传地址…"
                val result = withContext(Dispatchers.IO) {
                    trace.stage("image_bounds")
                    // Read encoded dimensions only: decodeStream returns null and allocates no bitmap pixels.
                    val bounds = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        inScaled = false
                    }
                    contentResolver.openInputStream(uri).use { input ->
                        checkNotNull(input) { "无法读取图片" }
                        BitmapFactory.decodeStream(input, null, bounds)
                    }
                    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解析图片宽高" }
                    trace.event("success", "width=${bounds.outWidth} height=${bounds.outHeight}")
                    trace.stage("init", "POST ${ClientManager.MEDIA_API_HOST}v1/media/upload/init scene=demo contentType=$type size=$size")
                    val init = Client.mediaAPI.initUpload(MediaUploadInitRequest("demo", type, size))
                    trace.event("success", "mediaId=${init.mediaId} method=${init.method} expiresAt=${init.expiresAt} headerCount=${init.headers.size}")
                    trace.stage("cos_upload", "mediaId=${init.mediaId}")
                    val body = UriRequestBody(uri, type, size, trace) { sent ->
                        runOnUiThread { progress.progress = (sent * 100 / size).toInt() }
                    }
                    val request = Request.Builder().url(init.uploadUrl).put(body).apply {
                        init.headers.forEach { (key, value) -> header(key, value) }
                    }.build()
                    trace.event("request", "method=${request.method} host=${request.url.host} size=$size connectTimeoutMs=${http.connectTimeoutMillis} writeTimeoutMs=${http.writeTimeoutMillis} readTimeoutMs=${http.readTimeoutMillis}")
                    http.newCall(request).execute().use { response ->
                        trace.event("response", "http=${response.code} protocol=${response.protocol} requestId=${response.header("x-cos-request-id")} etag=${response.header("ETag")}")
                        check(response.isSuccessful) { "COS 上传失败：HTTP ${response.code}" }
                        val etag = response.header("ETag")
                        trace.stage("complete", "POST ${ClientManager.MEDIA_API_HOST}v1/media/upload/complete mediaId=${init.mediaId} contentType=$type size=$size etag=$etag width=${bounds.outWidth} height=${bounds.outHeight}")
                        val media = Client.mediaAPI.completeUpload(
                            MediaUploadCompleteRequest(
                                init.mediaId, init.objectKey, type, size, etag,
                                width = bounds.outWidth, height = bounds.outHeight,
                            )
                        )
                        trace.event("response", "mediaId=${media.id} contentType=${media.contentType} size=${media.size} width=${media.width} height=${media.height} createdAt=${media.createdAt}")
                        check(media.width == bounds.outWidth && media.height == bounds.outHeight) {
                            "服务端返回的图片宽高不匹配"
                        }
                        trace.event("success", "mediaId=${media.id} contentType=${media.contentType} size=${media.size} width=${media.width} height=${media.height}")
                        logPreviewUrl(MediaDownloadUrlResponse(media.id, media.url, media.expiresAt), trace)
                        media
                    }
                }
                latestMediaId = result.id
                latestDownloadUrl = MediaDownloadUrlResponse(result.id, result.url, result.expiresAt)
                status.text = "上传完成：${result.id}"
                download.visibility = Button.VISIBLE
                trace.event("upload_finished", "mediaId=${result.id}")
            } catch (error: CancellationException) {
                trace.event("cancelled")
                throw error
            } catch (error: Exception) {
                trace.failure(error)
                status.text = "上传失败：${error.message ?: "未知错误"}"
            } finally {
                select.isEnabled = true
                download.isEnabled = latestMediaId != null
            }
        }
    }

    private fun openDownloadUrl() {
        val mediaId = latestMediaId ?: return
        val trace = MediaUploadTrace("download_url")
        download.isEnabled = false
        status.text = "正在申请下载地址…"
        lifecycleScope.launch {
            try {
                // Reuse completion's URL while valid; refresh shortly before its signature expires.
                val cached = latestDownloadUrl?.takeIf {
                    it.mediaId == mediaId && it.expiresAt > System.currentTimeMillis() + 5_000L
                }
                val result = if (cached != null) {
                    trace.stage("cached_preview", "mediaId=$mediaId")
                    logPreviewUrl(cached, trace)
                    cached
                } else {
                    requestDownloadUrl(mediaId, trace).also { latestDownloadUrl = it }
                }
                trace.stage("open_browser", "host=${result.url.toHttpUrlOrNull()?.host}")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                status.text = "已打开 COS 直连下载地址"
                trace.event("success")
            } catch (error: CancellationException) {
                trace.event("cancelled")
                throw error
            } catch (error: Exception) {
                trace.failure(error)
                status.text = "下载地址获取失败：${error.message ?: "未知错误"}"
            } finally {
                download.isEnabled = true
            }
        }
    }

    private suspend fun requestDownloadUrl(
        mediaId: String,
        trace: MediaUploadTrace,
    ): MediaDownloadUrlResponse {
        trace.stage("download_url", "GET ${ClientManager.MEDIA_API_HOST}v1/media/$mediaId/download-url mediaId=$mediaId")
        val result = withContext(Dispatchers.IO) { Client.mediaAPI.downloadUrl(mediaId) }
        logPreviewUrl(result, trace)
        return result
    }

    private fun logPreviewUrl(result: MediaDownloadUrlResponse, trace: MediaUploadTrace) {
        val host = result.url.toHttpUrlOrNull()?.host
        trace.event("success", "mediaId=${result.mediaId} host=$host expiresAt=${result.expiresAt} hostMatchesExpected=${host == "media.pixshaft.com"}")
        // Debug-only, explicitly requested for copying the complete signed preview URL.
        trace.event("preview_url", "url=${result.url}")
    }

    private inner class UriRequestBody(
        private val uri: Uri,
        private val contentType: String,
        private val length: Long,
        private val trace: MediaUploadTrace,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            val started = System.nanoTime()
            var lastLogAt = started
            var lastPercent = 0L
            trace.event("body_start", "size=$length")
            // Reset progress bookkeeping on every writeTo, including OkHttp retries.
            try {
                contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "无法读取图片" }
                    trace.event("stream_opened")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        val now = System.nanoTime()
                        val percent = sent * 100 / length
                        if (percent >= lastPercent + 10 || now - lastLogAt >= 1_000_000_000L) {
                            val elapsedMs = ((now - started) / 1_000_000).coerceAtLeast(1)
                            trace.event("progress", "sent=$sent total=$length percent=$percent bodyMs=$elapsedMs bytesPerSecond=${sent * 1000 / elapsedMs}")
                            lastLogAt = now
                            lastPercent = percent
                        }
                        onProgress(sent)
                    }
                }
            } catch (error: Exception) {
                trace.event("body_failed", "sent=$sent expected=$length type=${error.javaClass.simpleName} bodyMs=${(System.nanoTime() - started) / 1_000_000}")
                throw error
            }
            trace.event("body_finished", "sent=$sent expected=$length sizeMatches=${sent == length} bodyMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
}
