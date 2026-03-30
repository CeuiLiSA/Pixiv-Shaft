package ceui.pixiv.ui.upscale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object JaZhTranslator {

    suspend fun translate(text: String): String {
        if (text.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://translate.googleapis.com/translate_a/single")
                val params = "client=gtx&sl=ja&tl=zh-CN&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(params.toByteArray()) }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val arr = JSONArray(response)
                val sentences = arr.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    sb.append(sentences.getJSONArray(i).getString(0))
                }
                val result = sb.toString()
                Timber.d("JaZhTranslator: '$text' → '$result'")
                result
            } catch (e: Exception) {
                Timber.e(e, "JaZhTranslator: failed for '$text'")
                text
            }
        }
    }

    suspend fun translateBatch(texts: List<String>): List<String> {
        return texts.map { translate(it) }
    }
}
