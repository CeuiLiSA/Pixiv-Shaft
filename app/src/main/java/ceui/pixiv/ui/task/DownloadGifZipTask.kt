package ceui.pixiv.ui.task

import ceui.lisa.activities.Shaft
import ceui.lisa.models.GifResponse
import ceui.loxia.Client
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File

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

        val zipChild = File(zipParent, "zip_file_${illustId}.zip")
        if (!zipChild.exists()) zipChild.createNewFile()

        val key = KEY + illustId
        if (_prefStore.getBoolean(key, false) && zipChild.length() > 100L) {
            onEnd(zipChild)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(zipFileUrl)
                    .header("Referer", "https://www.pixiv.net/artworks/$illustId").build()
                Client.shaftClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")

                    response.body.byteStream().use { input ->
                        zipChild.sink().buffer().use { output ->
                            input.copyTo(output.outputStream())
                        }
                    }
                }

                onEnd(zipChild)
                _prefStore.putBoolean(key, true)
            } catch (ex: Exception) {
                onError(ex)
            }
        }
    }

    companion object {
        private const val KEY = "GIF_ZIP_FILE_KEY_"
    }
}