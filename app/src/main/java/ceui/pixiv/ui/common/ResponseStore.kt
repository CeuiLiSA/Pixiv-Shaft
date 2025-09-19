package ceui.pixiv.ui.common

import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.GSON_DEFAULT
import com.tencent.mmkv.MMKV
import timber.log.Timber

class ResponseStore<T> private constructor(
    private val keyProvider: () -> String,
    private val expirationTimeMillis: Long,
    private val typeToken: Class<T>
) {

    private val jsonKey: String
        get() = "json-key-${keyProvider()}"

    private val timeKey: String
        get() = "time-key-${keyProvider()}"

    private val preferences: MMKV by lazy {
        MMKV.mmkvWithID("api-cache-${SessionManager.loggedInUid}")
    }

    fun writeToCache(data: T) {
        val currentTime = System.currentTimeMillis()
        preferences.putString(jsonKey, GSON_DEFAULT.toJson(data))
        preferences.putLong(timeKey, currentTime)
    }

    fun isCacheExpired(): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheTimestamp = preferences.getLong(timeKey, 0L)
        return (currentTime - cacheTimestamp) > expirationTimeMillis
    }

    fun loadFromCache(): T? {
        return try {
            val json = preferences.getString(jsonKey, null)
            if (json?.isNotEmpty() == true) {
                GSON_DEFAULT.fromJson(json, typeToken)
            } else {
                null
            }
        } catch (ex: Exception) {
            Timber.e(ex)
            null
        }
    }

    companion object {
        fun <T> create(
            keyProvider: () -> String,
            expirationTimeMillis: Long,
            typeToken: Class<T>
        ): ResponseStore<T> {
            return ResponseStore(keyProvider, expirationTimeMillis, typeToken)
        }
    }
}

inline fun <reified T : Any> createResponseStore(
    noinline keyProvider: () -> String,
    expirationTimeMillis: Long = 5 * 60 * 1000L,
): ResponseStore<T> {
    return ResponseStore.create(
        keyProvider = keyProvider,
        expirationTimeMillis = expirationTimeMillis,
        typeToken = T::class.java,
    )
}