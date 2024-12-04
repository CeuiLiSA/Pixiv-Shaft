package ceui.lisa.fragments

import android.content.Context
import ceui.lisa.models.NovelDetail
import ceui.lisa.utils.Common
import ceui.loxia.PixivHtmlObject
import ceui.loxia.SpaceHolder
import ceui.loxia.WebNovel
import ceui.loxia.novel.NovelChapterHolder
import ceui.loxia.novel.NovelImageHolder
import ceui.loxia.novel.NovelTextHolder
import ceui.pixiv.ui.common.ListItemHolder
import com.google.gson.Gson
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Response

abstract class WebNovelParser(response: Response<ResponseBody>) {

    init {
        try {
            val html = response.body()?.string() ?: ""
            parsePixivObject(html)?.let { pixivHtmlObject ->
                pixivHtmlObject.novel?.let { webNovel ->
                    onNovelPrepared(NovelDetail().apply {
                        novel_text = webNovel.text
                        series_next = webNovel.seriesNavigation?.nextNovel
                        series_prev = webNovel.seriesNavigation?.prevNovel
                        novel_marker = if (webNovel.marker == null) NovelDetail.NovelMarkerBean() else webNovel.marker
                    }, webNovel)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    abstract fun onNovelPrepared(novelDetail: NovelDetail, webNovel: WebNovel)

    companion object {

        fun parsePixivObject(html: String): PixivHtmlObject? {
            // 使用Jsoup解析HTML字符串
            val doc: Document = Jsoup.parse(html)

            // 提取所有<script>标签的内容
            val scripts = doc.getElementsByTag("script")

            // 寻找包含 'Object.defineProperty(window, 'pixiv'' 的脚本
            for (script in scripts) {
                val scriptContent = script.html()

                // 查找包含 'Object.defineProperty(window, 'pixiv' 的部分
                if (scriptContent.contains("Object.defineProperty(window, 'pixiv'")) {
                    // 提取 pixiv 对象字符串（这里假设 scriptContent 是 JSON 格式）
                    val start = scriptContent.indexOf("value: {") + 7
                    val end = scriptContent.indexOf("});", start)
                    val regex = ",(?=\\s*[}\\]])".toRegex()
                    val pixivJson = scriptContent.substring(start, end).trim().replace(regex, "")
                    // 使用 Gson 将字符串解析为 Kotlin 对象
                    val gson = Gson()
                    return gson.fromJson(pixivJson, PixivHtmlObject::class.java)
                }
            }

            return null
        }

        fun buildNovelHolders(mWebNovel: WebNovel?, s: String): List<ListItemHolder> {
            if (mWebNovel == null) {
                return listOf()
            }

            val uploadedImageMark = "[uploadedimage:"
            val pixivImageMark = "[pixivimage:"

            val holderList = mutableListOf<ListItemHolder>()

            if (s.contains(uploadedImageMark)) {
                var id = 0L
                val startIndex: Int = s.indexOf(uploadedImageMark) + uploadedImageMark.length
                val endIndex: Int = s.indexOf("]")
                try {
                    id = s.substring(startIndex, endIndex).toLong()
                } catch (exception: java.lang.Exception) {
                    exception.printStackTrace()
                }
                holderList.add(
                    NovelImageHolder(
                        NovelImageHolder.Type.UploadedImage,
                        id,
                        0,
                        mWebNovel
                    )
                )
            } else if (s.contains(pixivImageMark)) {
                var id = 0L
                val startIndex: Int = s.indexOf(pixivImageMark) + pixivImageMark.length
                val endIndex: Int = s.indexOf("]")
                val result: String = s.substring(startIndex, endIndex)
                var indexInIllust = 0
                try {
                    if (result.contains("-")) {
                        val ret = result.split("-".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray()
                        indexInIllust = ret[1].toInt()
                        id = ret[0].toLong()
                    } else {
                        id = result.toLong()
                    }
                } catch (exception: java.lang.Exception) {
                    exception.printStackTrace()
                }
                holderList.add(
                    NovelImageHolder(
                        NovelImageHolder.Type.PixivImage,
                        id,
                        indexInIllust,
                        mWebNovel
                    )
                )
            } else if (s.contains("[newpage]")) {
                holderList.add(SpaceHolder())
            } else if (s.contains("[chapter:")) {
                holderList.add(NovelChapterHolder(extractChapterContent(s) ?: "", Common.getNovelTextColor()))
            } else {
                holderList.add(NovelTextHolder(s, Common.getNovelTextColor()))
            }
            return holderList
        }

        private fun extractChapterContent(input: String): String? {
            val regex = """\[chapter:(.+?)]""".toRegex()
            val matchResult = regex.find(input)
            return matchResult?.groups?.get(1)?.value
        }
    }
}