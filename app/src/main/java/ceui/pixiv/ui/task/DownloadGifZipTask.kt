package ceui.pixiv.ui.task

import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.models.GifResponse
import ceui.loxia.Client
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class DownloadGifZipTask(
    private val illustId: Long,
    private val gifResponse: GifResponse,
    private val gifState: MutableLiveData<GifState>,
) : QueuedRunnable<File>() {

    private val _prefStore by lazy { MMKV.mmkvWithID("gif-resp") }

    override suspend fun execute() {
        val zipFileUrl = gifResponse.ugoira_metadata.zip_urls?.maxImage
            ?: throw IllegalArgumentException("Zip URL is null")

        val zipParent = File(Shaft.getContext().filesDir, "ShaftGifZip")
        if (!zipParent.exists()) zipParent.mkdir()

        val unzipFolder = File(zipParent, "zip_file_${illustId}_unzipped")
        if (!unzipFolder.exists()) unzipFolder.mkdir()

        val webpFile = File(zipParent, "ugoira_$illustId.webp")

        val key = KEY + illustId
        if (_prefStore.getBoolean(key, false) && webpFile.exists()) {
            gifState.postValue(GifState.Done(webpFile))
            onEnd(webpFile)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // ✅ 仅下载（更新 0..100）
                val zipFile = downloadZip(zipFileUrl, zipParent)

                // ✅ 解压（不更新进度）
                val imageFiles = unzip(zipFile, unzipFolder)
                if (imageFiles.isEmpty()) throw Exception("No images in zip")

                encodeGifByFFmpeg(unzipFolder, webpFile)
            } catch (ex: Exception) {
                onError(ex)
            }
        }
    }

    private fun encodeGifByFFmpeg(unzipFolder: File, webpFile: File) {
        val listFile = File(unzipFolder, "file_list.txt")
        gifResponse.ugoira_metadata?.frames?.takeIf { it.isNotEmpty() }?.let { frames ->
            listFile.printWriter().use { pw ->
                frames.forEach { frame ->
                    val filePath =
                        File(unzipFolder, frame.file).absolutePath.replace("'", "'\\''")
                    pw.println("file '$filePath'")
                    val durationSec = frame.delay / 1000F
                    pw.println("duration $durationSec")
                }
                val lastFilePath =
                    File(unzipFolder, frames.last().file).absolutePath.replace("'", "'\\''")
                pw.println("file '$lastFilePath'")
            }
        }

        val ret = io.github.tobelogin.FormatConverter.list2webp(listFile.absolutePath, webpFile.absolutePath)
        if (ret == 0) {
            val key = KEY + illustId
            _prefStore.putBoolean(key, true)

            unzipFolder.deleteRecursively()

            val sizeKb = webpFile.length() / 1024.0
            Timber.d("GifTaskAAAA WebP generated: ${webpFile.absolutePath}")
            Timber.d("GifTaskAAAA ${String.format("File size: %.2f KB", sizeKb)}")

            onEnd(webpFile)
            gifState.postValue(GifState.Done(webpFile))
        } else {
            onError(Exception("FFmpeg failed with rc=$ret"))
        }
    }

    private fun downloadZip(
        zipUrl: String,
        targetFolder: File,
    ): File {
        val listener = object : KProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                if (contentLength > 0) {
                    val percent = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                    gifState.postValue(GifState.DownloadZip(percent))
                } else {
                    gifState.postValue(GifState.DownloadZip(0))
                }
            }
        }

        val request = Request.Builder()
            .url(zipUrl)
            .header("Referer", "https://www.pixiv.net/artworks/$illustId")
            .tag(KProgressListener::class.java, listener)
            .build()

        val clientWithProgress = Client.shaftClient.newBuilder()
            .addNetworkInterceptor(ProgressInterceptor())
            .build()

        val zipFile = File(targetFolder, "tmp_${illustId}.zip").apply { parentFile?.mkdirs() }

        clientWithProgress.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")

            val body = response.body
            body.byteStream().use { input ->
                FileOutputStream(zipFile).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        fos.write(buf, 0, r)
                    }
                }
            }
        }

        gifState.postValue(GifState.Encode)
        return zipFile
    }

    private fun unzip(zipFile: File, targetFolder: File): List<File> {
        val files = mutableListOf<File>()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            for (entry in entries) {
                val outFile = File(targetFolder, entry.name)
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { ins ->
                    FileOutputStream(outFile).use { fos ->
                        ins.copyTo(fos)
                    }
                }
                files.add(outFile)
            }
        }
        return files
    }

    companion object {
        private const val KEY = "GIF_ZIP_FILE_KEY_"
    }
}
