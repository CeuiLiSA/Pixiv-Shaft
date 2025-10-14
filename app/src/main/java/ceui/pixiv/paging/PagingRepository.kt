package ceui.pixiv.paging

import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.Event
import ceui.loxia.KListShow
import ceui.pixiv.utils.GSON_DEFAULT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Type

abstract class PagingRepository<ObjectT> {

    private var responseType: Type? = null  // 缓存泛型类型信息

    val errorEvent = MutableLiveData<Event<Throwable>>()

    suspend fun load(nextUrl: String?): KListShow<ObjectT> {
        if (nextUrl == null) {
            return loadFirst().also {
                responseType = getResponseTypeFromInstance(it)
            }
        }

        return loadNext(nextUrl)
    }

    abstract suspend fun loadFirst(): KListShow<ObjectT>

    private suspend fun loadNext(nextUrl: String): KListShow<ObjectT> {
        return withContext(Dispatchers.IO) {
            val responseBody = Client.appApi.generalGet(nextUrl)
            val responseJson = responseBody.string()
            val type = responseType
                ?: error("Response type not initialized. Must call load(null) first.")

            GSON_DEFAULT.fromJson(responseJson, type)
        }
    }

    private fun getResponseTypeFromInstance(instance: KListShow<ObjectT>): Type {
        return instance::class.java
    }
}
