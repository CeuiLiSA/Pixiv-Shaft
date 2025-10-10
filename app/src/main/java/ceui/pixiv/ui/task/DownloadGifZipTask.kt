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
import java.util.zip.ZipFile

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
                // ✅ 仅下载（更新 0..100）
                val zipFile = downloadZip(zipFileUrl, zipParent) { p ->
                    progressLiveData.postValue(p)
                }

                // ✅ 解压（不更新进度）
                val imageFiles = unzip(zipFile, unzipFolder)
                if (imageFiles.isEmpty()) throw Exception("No images in zip")

                // 按顺序排序
                val sortedFiles = imageFiles.sortedBy { it.name }

                // 生成 FFmpeg concat 文件
                val listFile = File(unzipFolder, "file_list.txt")
                listFile.printWriter().use { pw ->
                    sortedFiles.forEach { f ->
                        val path = f.absolutePath.replace("'", "'\\''")
                        pw.println("file '$path'")
                        pw.println("duration 0.08")
                    }
                    pw.println("file '${sortedFiles.last().absolutePath.replace("'", "'\\''")}'")
                }

                // ✅ FFmpeg 转 WebP
                val cmd =
                    "-y -f concat -safe 0 -i ${listFile.absolutePath} -loop 0 ${webpFile.absolutePath}"

                com.arthenica.mobileffmpeg.FFmpeg.executeAsync(cmd) { _, returnCode ->
                    if (returnCode == 0) {
                        _prefStore.putBoolean(key, true)
                        progressLiveData.postValue(100)
                        // ✅ 删除临时解压文件夹
                        unzipFolder.deleteRecursively()
                        onEnd(webpFile)
                    } else {
                        onError(Exception("FFmpeg failed with rc=$returnCode"))
                    }
                }

            } catch (ex: Exception) {
                onError(ex)
            }
        }
    }

    /**
     * 仅负责下载 zip 文件（更新 0..100）
     */
    private fun downloadZip(
        zipUrl: String,
        targetFolder: File,
        onProgress: (Int) -> Unit
    ): File {
        val request = Request.Builder()
            .url(zipUrl)
            .header("Referer", "https://www.pixiv.net/artworks/$illustId")
            .build()

        val zipFile = File(targetFolder, "tmp_${illustId}.zip")
        zipFile.parentFile?.mkdirs()

        Client.shaftClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")

            val body = response.body
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            val buf = ByteArray(4 * 1024)

            body.byteStream().use { input ->
                FileOutputStream(zipFile).use { fos ->
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        fos.write(buf, 0, r)
                        downloaded += r
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                            onProgress(percent)
                        }
                    }
                }
            }
        }

        // 下载完成
        onProgress(100)
        return zipFile
    }

    /**
     * 解压 ZIP，不更新进度
     */
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
