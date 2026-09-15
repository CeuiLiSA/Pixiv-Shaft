package ceui.pixiv.shaftapi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.api.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val http = OkHttpClient()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_demo)
        status = findViewById(R.id.media_demo_status)
        progress = findViewById(R.id.media_demo_progress)
        select = findViewById(R.id.media_demo_select)
        download = findViewById(R.id.media_demo_download)
        select.setOnClickListener { pickImage.launch("image/*") }
        download.setOnClickListener { openDownloadUrl() }
    }

    private fun upload(uri: Uri) {
        val type = contentResolver.getType(uri) ?: run {
            status.text = "无法识别图片类型"
            return
        }
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        if (size <= 0L || size > 25L * 1024 * 1024) {
            status.text = "图片大小无效（最大 25 MB）"
            return
        }
        select.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        status.text = "正在申请直传地址…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val init = Client.mediaAPI.initUpload(MediaUploadInitRequest("demo", type, size))
                    val body = UriRequestBody(uri, type, size) { sent ->
                        runOnUiThread { progress.progress = (sent * 100 / size).toInt() }
                    }
                    val request = Request.Builder().url(init.uploadUrl).put(body).apply {
                        init.headers.forEach { (key, value) -> header(key, value) }
                    }.build()
                    http.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "COS 上传失败：HTTP ${response.code}" }
                        val etag = response.header("ETag")
                        Client.mediaAPI.completeUpload(
                            MediaUploadCompleteRequest(init.mediaId, init.objectKey, type, size, etag)
                        )
                    }
                    init.mediaId
                }
                latestMediaId = result
                status.text = "上传完成：$result"
                download.visibility = Button.VISIBLE
                download.isEnabled = true
            } catch (error: Exception) {
                status.text = "上传失败：${error.message ?: "未知错误"}"
            } finally {
                select.isEnabled = true
            }
        }
    }

    private fun openDownloadUrl() {
        val mediaId = latestMediaId ?: return
        download.isEnabled = false
        status.text = "正在申请下载地址…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { Client.mediaAPI.downloadUrl(mediaId) }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                status.text = "已打开 COS 直连下载地址"
            } catch (error: Exception) {
                status.text = "下载地址获取失败：${error.message ?: "未知错误"}"
            } finally {
                download.isEnabled = true
            }
        }
    }

    private inner class UriRequestBody(
        private val uri: Uri,
        private val contentType: String,
        private val length: Long,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法读取图片" }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
            }
        }
    }
}
