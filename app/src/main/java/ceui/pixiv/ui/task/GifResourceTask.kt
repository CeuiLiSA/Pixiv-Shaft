package ceui.pixiv.ui.task

import ceui.lisa.models.GifResponse
import ceui.loxia.Client
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import timber.log.Timber

class GifResourceTask(private val illustId: Long) : QueuedRunnable<GifResponse>() {

    private val _prefStore by lazy { MMKV.mmkvWithID("gif-resp") }
    private val _gson = Gson()

    override suspend fun execute() {
        try {
            val key = KEY + illustId
            val json = _prefStore.getString(key, null)

            val result: GifResponse = json
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    try {
                        _gson.fromJson(it, GifResponse::class.java)
                    } catch (e: Exception) {
                        Timber.e(e)
                        null
                    }
                } ?: run {
                val resp = Client.appApi.getGifPackage(illustId)
                _prefStore.putString(key, _gson.toJson(resp))
                resp
            }

            Timber.d("GifResourceTask fetched: $json")
            onEnd(result)
        } catch (ex: Exception) {
            onError(ex)
        }
    }

    companion object {
        private const val KEY = "GIF_RESPONSE_KEY_"
    }
}