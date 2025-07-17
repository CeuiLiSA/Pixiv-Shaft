package ceui.pixiv.paging

import ceui.loxia.Client
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.ListItemHolder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class PagingAPIRepository<ObjectT> {

    private val gson = Gson()

    abstract val recordType: Int

    suspend fun load(nextUrl: String?): KListShow<ObjectT> {
        if (nextUrl == null) {
            return loadFirst()
        }

        return loadNext(nextUrl)
    }

    abstract suspend fun loadFirst(): KListShow<ObjectT>

    private suspend fun loadNext(nextUrl: String): KListShow<ObjectT> {
        return withContext(Dispatchers.IO) {
            val responseBody = Client.appApi.generalGet(nextUrl)
            val responseJson = responseBody.string()
            gson.fromJson(responseJson, IllustResponse::class.java) as KListShow<ObjectT>
        }
    }

    abstract fun mapper(entity: GeneralEntity): List<ListItemHolder>
}