package ceui.pixiv.ui.novel

import android.graphics.Color
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.PixivHtmlObject
import ceui.loxia.novel.NovelTextHolder
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.task.HumanReadableTask
import com.google.gson.Gson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private fun parsePixivObject(html: String): PixivHtmlObject? {
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

class NovelTextDataSource(private val novelId: Long) : DataSource<String, KListShow<String>>(
    dataFetcher = {
        val resp = Client.appApi.getNovelText(novelId).string()
        object : KListShow<String> {
            override val displayList: List<String>
                get() = parsePixivObject(resp)?.novel?.text?.split("\n") ?: listOf()
            override val nextPageUrl: String?
                get() = null
        }
    },
    itemMapper = { text -> listOf(NovelTextHolder(text, Color.WHITE)) }
)