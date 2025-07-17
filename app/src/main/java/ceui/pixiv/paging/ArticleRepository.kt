package ceui.pixiv.paging

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.loxia.ObjectType
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ArticleRepository {

    private val gson = Gson()

    suspend fun loadImpl(nextUrl: String?): KListShow<Illust> {
        delay(2000L)
        if (nextUrl == null) {
            return Client.appApi.getHomeData(ObjectType.ILLUST)
        }

        return withContext(Dispatchers.IO) {
            val responseBody = Client.appApi.generalGet(nextUrl)
            val responseJson = responseBody.string()
            gson.fromJson(responseJson, IllustResponse::class.java)
        }
    }
}
