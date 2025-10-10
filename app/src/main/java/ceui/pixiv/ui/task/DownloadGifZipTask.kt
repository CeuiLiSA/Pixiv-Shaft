package ceui.pixiv.ui.task

import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.models.GifResponse
import ceui.loxia.Client
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class DownloadGifZipTask(
    private val illustId: Long,
    private val gifResponse: GifResponse,
    private val progressLiveData: MutableLiveData<Int>,
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
            progressLiveData.value = 100
            onEnd(webpFile)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // 1️⃣ 下载并解压
                val imageFiles = downloadAndUnzip(zipFileUrl, unzipFolder) { p ->
                    // 更新进度 0~50，下载+解压阶段占 50%
                    progressLiveData.postValue(p / 2)
                }

                if (imageFiles.isEmpty()) throw Exception("No images in zip")

                // 2️⃣ 按顺序排序
                val sortedFiles = imageFiles.sortedBy { it.name } // jpeg 或 png

                // 3️⃣ 生成 FFmpeg concat 文件
                val listFile = File(unzipFolder, "file_list.txt")
                listFile.printWriter().use { pw ->
                    sortedFiles.forEach { f ->
                        pw.println("file '${f.absolutePath}'")
                        pw.println("duration 0.08") // 每帧 80ms
                    }
                    // 最后一帧需要重复
                    pw.println("file '${sortedFiles.last().absolutePath}'")
                }

                // 4️⃣ FFmpeg 转 WebP
                val cmd =
                    "-y -f concat -safe 0 -i ${listFile.absolutePath} -loop 0 ${webpFile.absolutePath}"
                // 注册 FFmpeg 回调更新进度
                val rc = com.arthenica.mobileffmpeg.FFmpeg.executeAsync(cmd) { _, returnCode ->
                    if (returnCode == 0) {
                        progressLiveData.postValue(100) // FFmpeg 完成
                    } else {
                        onError(Exception("FFmpeg failed with rc=$returnCode"))
                    }
                }

                if (rc == 0L) {
                    _prefStore.putBoolean(key, true)
                    onEnd(webpFile)
                }
            } catch (ex: Exception) {
                onError(ex)
            }
        }
    }

    /**
     * 下载并解压 Zip
     * @param onProgress 0~100 回调
     */
    private fun downloadAndUnzip(
        zipUrl: String,
        targetFolder: File,
        onProgress: (Int) -> Unit
    ): List<File> {
        val request = Request.Builder()
            .url(zipUrl)
            .header("Referer", "https://www.pixiv.net/artworks/$illustId")
            .build()

        Client.shaftClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")

            val body = response.body
            val totalBytes = body.contentLength()
            var bytesRead = 0L
            val buffer = ByteArray(4096)

            val files = mutableListOf<File>()

            ZipInputStream(body.byteStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val filePath = File(targetFolder, entry.name)
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        filePath.parentFile?.mkdirs()
                        FileOutputStream(filePath).use { fos ->
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                                bytesRead += read
                                if (totalBytes > 0) {
                                    val percent = (bytesRead * 50 / totalBytes).toInt()
                                    onProgress(percent) // 下载+解压进度
                                }
                            }
                        }
                        files.add(filePath)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return files
        }
    }

    companion object {
        private const val KEY = "GIF_ZIP_FILE_KEY_"
    }
}
