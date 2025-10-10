package ceui.pixiv.ui.task

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
    private val gifResponse: GifResponse
) : QueuedRunnable<File>() {

    private val _prefStore by lazy { MMKV.mmkvWithID("gif-resp") }

    override suspend fun execute() {
        val zipFileUrl = gifResponse.ugoira_metadata.zip_urls?.maxImage
            ?: throw IllegalArgumentException("Zip URL is null")

        val zipParent = File(Shaft.getContext().filesDir, "ShaftGifZip")
        if (!zipParent.exists()) zipParent.mkdir()

        val unzipFolder = File(zipParent, "zip_file_${illustId}_unzipped")
        if (!unzipFolder.exists()) unzipFolder.mkdir()

        val key = KEY + illustId
        if (_prefStore.getBoolean(key, false) && unzipFolder.exists() && unzipFolder.listFiles()
                ?.isNotEmpty() == true
        ) {
            onEnd(unzipFolder)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(zipFileUrl)
                    .header("Referer", "https://www.pixiv.net/artworks/$illustId")
                    .build()

                Client.shaftClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError(Exception("Download failed: ${response.code}"))
                        return@use
                    }

                    ZipInputStream(response.body.byteStream()).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            val filePath = File(unzipFolder, entry.name)
                            if (entry.isDirectory) {
                                filePath.mkdirs()
                            } else {
                                filePath.parentFile?.mkdirs()
                                FileOutputStream(filePath).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }

                            zis.closeEntry()
                            entry = zis.nextEntry
                        }

                        _prefStore.putBoolean(key, true)
                        onEnd(unzipFolder)
                    }
                }

            } catch (ex: Exception) {
                onError(ex)
            }
        }
    }

    companion object {
        private const val KEY = "GIF_ZIP_FILE_KEY_"
    }
}
