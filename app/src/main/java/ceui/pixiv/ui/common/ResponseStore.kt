package ceui.pixiv.ui.common

import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.delay

class ResponseStore<T>(
    private val keyProvider: () -> String,
    private val expirationTimeMillis: Long,
    private val typeToken: Class<T>,
    private val dataLoader: suspend () -> T
) {

    private val gson = Gson()

    private val jsonKey: String
        get() = "json-key-${keyProvider()}"

    private val timeKey: String
        get() = "time-key-${keyProvider()}"

    private val preferences: MMKV by lazy {
        MMKV.mmkvWithID("api-cache")
    }

    suspend fun retrieveData(): T {
        val cacheTimestamp = preferences.getLong(timeKey, 0L)
        val currentTime = System.currentTimeMillis()

        return if (isCacheExpired(cacheTimestamp, currentTime)) {
            fetchAndCacheData(currentTime)
        } else {
            loadFromCache(currentTime)
        }
    }

    private fun isCacheExpired(cacheTimestamp: Long, currentTime: Long): Boolean {
        return (currentTime - cacheTimestamp) > expirationTimeMillis
    }

    private suspend fun fetchAndCacheData(currentTime: Long): T {
        val data = dataLoader()
        preferences.putString(jsonKey, gson.toJson(data))
        preferences.putLong(timeKey, currentTime)
        return data
    }

    private suspend fun loadFromCache(currentTime: Long): T {
        val json = preferences.getString(jsonKey, null)
        return try {
            delay(200L)
            gson.fromJson(json, typeToken)
        } catch (e: Exception) {
            fetchAndCacheData(currentTime)
        }
    }
}
