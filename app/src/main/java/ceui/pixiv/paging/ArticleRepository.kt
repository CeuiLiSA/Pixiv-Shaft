package ceui.pixiv.paging

import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

class ArticleRepository {

    private val gson = Gson()
    private val mmkv = MMKV.defaultMMKV()

    suspend fun loadImpl(nextUrl: String?): KListShow<Illust> {
        delay(2000L)
        if (nextUrl == null) {
//            val resp = Client.appApi.getHomeData(ObjectType.ILLUST)
//            mmkv.putString("hello-paging3-first", gson.toJson(resp))
            val resp = gson.fromJson(
                mmkv.getString("hello-paging3-first", ""),
                HomeIllustResponse::class.java
            )
            Timber.d("adsadsadsdsaw2 nextUrl == null ${resp.displayList.size}")
            return resp
        }

        return withContext(Dispatchers.IO) {
//            val responseBody = Client.appApi.generalGet(nextUrl)
//            val responseJson = responseBody.string()
//            mmkv.putString("hello-paging3-next", responseJson)
            val responseJson = mmkv.getString("hello-paging3-next", "")
            val resp = gson.fromJson(responseJson, IllustResponse::class.java)
            Timber.d("adsadsadsdsaw2 nextUrl != null ${resp.displayList.size}")
            resp
        }
    }
}
