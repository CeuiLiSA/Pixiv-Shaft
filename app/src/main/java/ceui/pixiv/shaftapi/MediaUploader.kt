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
    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        onProgress: (Int) -> Unit,
    ): MediaObject =
        withContext(Dispatchers.IO) {
            val trace = MediaUploadTrace("plaza_upload")
            try {
                trace.stage("metadata")
                val type = resolver.getType(uri)
                if (type !in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
                    throw MediaUploadException(MediaUploadException.Reason.TYPE)
                val reportedSize =
                    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                    } ?: -1L
                val size =
                    if (reportedSize > 0) reportedSize
                    else
                        try {
                            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                        } catch (_: java.io.IOException) {
                            -1L
                        }
                trace.event(
                    "result",
                    "contentType=$type reportedSize=$reportedSize size=$size maxBytes=${25L * 1024 * 1024}",
                )
                if (size <= 0) throw MediaUploadException(MediaUploadException.Reason.SIZE_UNKNOWN)
                if (size > 25L * 1024 * 1024)
                    throw MediaUploadException(MediaUploadException.Reason.SIZE)
                trace.stage("image_bounds")
                val bounds =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        inScaled = false
                    }
                resolver.openInputStream(uri).use { input ->
                    if (input == null) throw MediaUploadException(MediaUploadException.Reason.READ)
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    throw MediaUploadException(MediaUploadException.Reason.BOUNDS)
                trace.event(
                    "success",
                    "width=${bounds.outWidth} height=${bounds.outHeight} size=$size",
                )
                trace.stage("init")
                val init =
                    Client.mediaAPI.initUpload(MediaUploadInitRequest("plaza", type!!, size), trace)
                if (init.method != "PUT")
                    throw MediaUploadException(MediaUploadException.Reason.METHOD)
                trace.stage("cos_upload", "mediaId=${init.mediaId}")
                val body =
                    object : RequestBody() {
                        override fun contentType() = type.toMediaType()

                        override fun contentLength() = size

                        override fun writeTo(sink: BufferedSink) {
                            val progress = MediaUploadProgress()
                            resolver.openInputStream(uri).use { input ->
                                if (input == null)
                                    throw MediaUploadException(MediaUploadException.Reason.READ)
                                val buffer = ByteArray(64 * 1024)
                                var sent = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    sent += read
                                    if (sent > size)
                                        throw MediaUploadException(
                                            MediaUploadException.Reason.CHANGED
                                        )
                                    sink.write(buffer, 0, read)
                                    if (progress.shouldReport(sent, size, System.nanoTime()))
                                        onProgress((sent * 95 / size).toInt())
                                }
                                if (sent != size)
                                    throw MediaUploadException(MediaUploadException.Reason.CHANGED)
                            }
                        }
                    }
                val request =
                    Request.Builder()
                        .url(init.uploadUrl)
                        .tag(MediaUploadTrace::class.java, trace)
                        .put(body)
                        .apply { init.headers.forEach { (k, v) -> header(k, v) } }
                        .build()
                val etag =
                    awaitOkHttpCall(MediaHttpTransport.storageClient.newCall(request)) { response ->
                        trace.event("response", "http=${response.code} host=${request.url.host}")
                        if (!response.isSuccessful)
                            throw MediaUploadException(
                                MediaUploadException.Reason.HTTP,
                                response.code,
                            )
                        response.header("ETag")
                    }
                trace.stage("complete")
                val media =
                    Client.mediaAPI.completeUpload(
                        MediaUploadCompleteRequest(
                            init.mediaId,
                            init.objectKey,
                            type,
                            size,
                            etag,
                            width = bounds.outWidth,
                            height = bounds.outHeight,
                        ),
                        trace,
                    )
                if (media.width != bounds.outWidth || media.height != bounds.outHeight)
                    throw MediaUploadException(MediaUploadException.Reason.DIMENSIONS)
                trace.event(
                    "success",
                    "mediaId=${media.id} width=${media.width} height=${media.height} expiresAt=${media.expiresAt}",
                )
                trace.event("preview_url", "url=${media.url}")
                onProgress(100)
                media
            } catch (e: Exception) {
                trace.failure(e)
                throw e
            }
        }
}
