package ceui.pixiv.shaftapi

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import ceui.pixiv.api.Client
import ceui.pixiv.ui.translate.awaitOkHttpCall
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.HttpException

/** Streams original bytes. Bounds decoding never allocates a pixel bitmap. */
internal object MediaUploader {
    private const val MAX_BYTES = 25L * 1024 * 1024

    /** A resumed PUT must finish inside the signature; shorter than this and we re-init instead. */
    private const val RESUME_MARGIN_MS = 60_000L

    private class Bounds(val width: Int, val height: Int)

    /**
     * Uploads one image. [resume] is the authorisation a previous attempt persisted after init;
     * the API keeps pending rows and accepts a repeated completion, so a retry first asks the API
     * to complete the existing object, repeats only the PUT when the bytes never landed, and
     * starts over only when that authorisation is unusable. [onResume] fires right after a fresh
     * init so the caller can persist the new authorisation before the PUT starts.
     */
    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        resume: MediaUploadResume?,
        onResume: suspend (MediaUploadResume) -> Unit,
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
                    "contentType=$type reportedSize=$reportedSize size=$size maxBytes=$MAX_BYTES",
                )
                if (size <= 0) throw MediaUploadException(MediaUploadException.Reason.SIZE_UNKNOWN)
                if (size > MAX_BYTES) throw MediaUploadException(MediaUploadException.Reason.SIZE)
                trace.stage("image_bounds")
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        inScaled = false
                    }
                resolver.openInputStream(uri).use { input ->
                    if (input == null) throw MediaUploadException(MediaUploadException.Reason.READ)
                    BitmapFactory.decodeStream(input, null, options)
                }
                if (options.outWidth <= 0 || options.outHeight <= 0)
                    throw MediaUploadException(MediaUploadException.Reason.BOUNDS)
                val bounds = Bounds(options.outWidth, options.outHeight)
                trace.event("success", "width=${bounds.width} height=${bounds.height} size=$size")

                // The file may have been edited since the previous attempt; only the same bytes
                // may finish the same pending object (the API verifies size and type on HEAD).
                val reusable = resume?.takeIf { it.contentType == type && it.size == size }
                if (reusable != null) {
                    trace.stage("resume", "mediaId=${reusable.mediaId}")
                    when (val outcome = resumed(reusable, bounds, trace)) {
                        is Resumed.Done -> {
                            onProgress(100)
                            return@withContext outcome.media
                        }
                        Resumed.RepeatPut -> {
                            put(resolver, uri, reusable, trace, onProgress)
                            val media = complete(reusable, bounds, trace)
                            onProgress(100)
                            return@withContext media
                        }
                        Resumed.StartOver -> Unit
                    }
                }

                trace.stage("init")
                val init =
                    Client.mediaAPI.initUpload(MediaUploadInitRequest("plaza", type!!, size), trace)
                if (init.method != "PUT")
                    throw MediaUploadException(MediaUploadException.Reason.METHOD)
                val fresh =
                    MediaUploadResume(
                        init.mediaId,
                        init.objectKey,
                        init.uploadUrl,
                        init.headers,
                        init.expiresAt,
                        type,
                        size,
                    )
                onResume(fresh)
                put(resolver, uri, fresh, trace, onProgress)
                val media = complete(fresh, bounds, trace)
                onProgress(100)
                media
            } catch (e: Exception) {
                trace.failure(e)
                throw e
            }
        }

    private sealed class Resumed {
        class Done(val media: MediaObject) : Resumed()

        object RepeatPut : Resumed()

        object StartOver : Resumed()
    }

    /**
     * Finishes a previous attempt. The API answers a completed or still-pending object with the
     * ready row, a never-landed PUT with `media_not_uploaded`, and a swept, foreign or mismatched
     * object with another 404/409, which means starting over. Transport failures and server
     * errors propagate so the next retry can try again with the same authorisation.
     */
    private suspend fun resumed(
        resume: MediaUploadResume,
        bounds: Bounds,
        trace: MediaUploadTrace,
    ): Resumed {
        val error =
            try {
                return Resumed.Done(complete(resume, bounds, trace))
            } catch (e: HttpException) {
                e
            }
        val code = errorCode(error)
        trace.event("resume_rejected", "http=${error.code()} error=$code")
        return when {
            error.code() == 409 && code == "media_not_uploaded" ->
                // Nothing landed. Repeat the PUT only while the signature has room to finish.
                if (resume.expiresAt - System.currentTimeMillis() >= RESUME_MARGIN_MS)
                    Resumed.RepeatPut
                else Resumed.StartOver
            error.code() == 404 || error.code() == 409 -> Resumed.StartOver
            else -> throw error
        }
    }

    private suspend fun complete(
        resume: MediaUploadResume,
        bounds: Bounds,
        trace: MediaUploadTrace,
    ): MediaObject {
        trace.stage("complete", "mediaId=${resume.mediaId}")
        val media =
            Client.mediaAPI.completeUpload(
                MediaUploadCompleteRequest(
                    resume.mediaId,
                    resume.objectKey,
                    resume.contentType,
                    resume.size,
                    null,
                    width = bounds.width,
                    height = bounds.height,
                ),
                trace,
            )
        if (media.width != bounds.width || media.height != bounds.height)
            throw MediaUploadException(MediaUploadException.Reason.DIMENSIONS)
        trace.event(
            "success",
            "mediaId=${media.id} width=${media.width} height=${media.height} expiresAt=${media.expiresAt}",
        )
        trace.event("preview_url", "url=${media.url}")
        return media
    }

    private suspend fun put(
        resolver: ContentResolver,
        uri: Uri,
        resume: MediaUploadResume,
        trace: MediaUploadTrace,
        onProgress: (Int) -> Unit,
    ) {
        trace.stage("cos_upload", "mediaId=${resume.mediaId}")
        val size = resume.size
        val body =
            object : RequestBody() {
                override fun contentType() = resume.contentType.toMediaType()

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
                                throw MediaUploadException(MediaUploadException.Reason.CHANGED)
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
                .url(resume.uploadUrl)
                .tag(MediaUploadTrace::class.java, trace)
                .put(body)
                .apply { resume.headers.forEach { (k, v) -> header(k, v) } }
                .build()
        awaitOkHttpCall(MediaHttpTransport.storageClient.newCall(request)) { response ->
            trace.event("response", "http=${response.code} host=${request.url.host}")
            if (!response.isSuccessful)
                throw MediaUploadException(MediaUploadException.Reason.HTTP, response.code)
        }
    }

    private fun errorCode(error: HttpException): String? =
        try {
            error.response()?.errorBody()?.string()?.let { raw ->
                JsonParser.parseString(raw).asJsonObject.get("error")?.asString
            }
        } catch (_: Exception) {
            null
        }
}
