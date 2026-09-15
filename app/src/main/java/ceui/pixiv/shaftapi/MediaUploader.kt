package ceui.pixiv.shaftapi

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import ceui.pixiv.api.Client
import ceui.pixiv.ui.translate.awaitOkHttpCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/** Streams original bytes. Bounds decoding never allocates a pixel bitmap. */
internal object MediaUploader {
    suspend fun upload(resolver: ContentResolver, uri: Uri, onProgress: (Int) -> Unit): MediaObject = withContext(Dispatchers.IO) {
        val trace = MediaUploadTrace("plaza_upload")
        try {
            trace.stage("metadata")
            val type = resolver.getType(uri)
            require(type in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "支持 JPG、PNG、WebP 和 GIF 图片" }
            val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
            require(size in 1..25L * 1024 * 1024) { "单张图片最大 25 MB，且必须能读取文件大小" }
            trace.stage("image_bounds")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            resolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法读取图片，请重新选择" }
                BitmapFactory.decodeStream(input, null, bounds)
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解析真实图片宽高，请重新选择" }
            trace.event("success", "width=${bounds.outWidth} height=${bounds.outHeight} size=$size")
            trace.stage("init")
            val init = Client.mediaAPI.initUpload(MediaUploadInitRequest("plaza", type!!, size), trace)
            check(init.method == "PUT") { "不支持的上传方式" }
            trace.stage("cos_upload", "mediaId=${init.mediaId}")
            val body = object : RequestBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    val progress = MediaUploadProgress()
                    resolver.openInputStream(uri).use { input ->
                        checkNotNull(input) { "无法读取图片" }
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sent += read
                            check(sent <= size) { "图片内容已改变，请重新选择" }
                            sink.write(buffer, 0, read)
                            if (progress.shouldReport(sent, size, System.nanoTime())) onProgress((sent * 95 / size).toInt())
                        }
                        check(sent == size) { "图片内容已改变，请重新选择" }
                    }
                }
            }
            val request = Request.Builder().url(init.uploadUrl).tag(MediaUploadTrace::class.java, trace).put(body)
                .apply { init.headers.forEach { (k, v) -> header(k, v) } }.build()
            val etag = awaitOkHttpCall(MediaHttpTransport.storageClient.newCall(request)) { response ->
                trace.event("response", "http=${response.code} host=${request.url.host}")
                check(response.isSuccessful) { "图片上传失败（${response.code}）" }
                response.header("ETag")
            }
            trace.stage("complete")
            val media = Client.mediaAPI.completeUpload(MediaUploadCompleteRequest(init.mediaId, init.objectKey, type, size,
                etag, width = bounds.outWidth, height = bounds.outHeight), trace)
            check(media.width == bounds.outWidth && media.height == bounds.outHeight) { "服务端图片尺寸不匹配" }
            trace.event("success", "mediaId=${media.id} width=${media.width} height=${media.height} expiresAt=${media.expiresAt}")
            trace.event("preview_url", "url=${media.url}")
            onProgress(100)
            media
        } catch (e: Exception) { trace.failure(e); throw e }
    }
}
