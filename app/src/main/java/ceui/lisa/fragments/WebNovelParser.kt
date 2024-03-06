package ceui.lisa.fragments

import ceui.lisa.models.NovelDetail
import ceui.lisa.models.WebNovel
import ceui.lisa.utils.Common
import com.google.gson.Gson
import okhttp3.ResponseBody
import retrofit2.Response

abstract class WebNovelParser(response: Response<ResponseBody>) {

    init {
        try {
            val html = response.body()?.string() ?: ""
            html.lines().forEach {
                if (it.contains("novel: {\"id\":")) {
                    val cleaned = it.trim()
                    val result = cleaned.substring(7, cleaned.length - 1)
                    val webNovel = Gson().fromJson(result, WebNovel::class.java)
                    Common.showLog("${webNovel.illusts}")
                    onNovelPrepared(NovelDetail().apply {
                        novel_text = webNovel.text
                        series_next = webNovel.seriesNavigation?.nextNovel
                        series_prev = webNovel.seriesNavigation?.prevNovel
                        novel_marker = webNovel.marker
                    }, webNovel)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    abstract fun onNovelPrepared(novelDetail: NovelDetail, webNovel: WebNovel)
}
