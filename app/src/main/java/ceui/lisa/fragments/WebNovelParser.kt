package ceui.lisa.fragments

import androidx.annotation.WorkerThread
import ceui.lisa.http.parseHtml
import ceui.lisa.models.NovelDetail
import ceui.pixiv.api.model.PixivHtmlObject
import ceui.pixiv.api.model.WebNovel
import com.google.gson.Gson
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Response
import java.io.IOException

abstract class WebNovelParser(response: Response<ResponseBody>) {

    init {
        try {
            val body = response.body()
            if (body != null) {
                parsePixivObject(body)?.let { pixivHtmlObject ->
                    pixivHtmlObject.novel?.let { webNovel ->
                        onNovelPrepared(NovelDetail().apply {
                            novel_text = webNovel.text
                            series_next = webNovel.seriesNavigation?.nextNovel
                            series_prev = webNovel.seriesNavigation?.prevNovel
                            novel_marker = if (webNovel.marker == null) NovelDetail.NovelMarkerBean() else webNovel.marker
                        }, webNovel)
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    abstract fun onNovelPrepared(novelDetail: NovelDetail, webNovel: WebNovel)

    companion object {

        @WorkerThread
        @Throws(IOException::class)
        fun parsePixivObject(body: ResponseBody): PixivHtmlObject? {
            return extractPixivObject(body.parseHtml())
        }

        fun parsePixivObject(html: String): PixivHtmlObject? {
            return extractPixivObject(Jsoup.parse(html))
        }

        private fun extractPixivObject(doc: Document): PixivHtmlObject? {
            val scripts = doc.getElementsByTag("script")
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("Object.defineProperty(window, 'pixiv'")) {
                    val start = scriptContent.indexOf("value: {") + 7
                    val end = scriptContent.indexOf("});", start)
                    val regex = ",(?=\\s*[}\\]])".toRegex()
                    val pixivJson = scriptContent.substring(start, end).trim().replace(regex, "")
                    val gson = Gson()
                    return gson.fromJson(pixivJson, PixivHtmlObject::class.java)
                }
            }
            return null
        }
    }
}
