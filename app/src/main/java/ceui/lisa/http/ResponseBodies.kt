package ceui.lisa.http

import androidx.annotation.WorkerThread
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * Blocking [ResponseBody] parsers. Call on a background thread / `Dispatchers.IO`.
 * The body is closed on both success and failure.
 *
 * Gson uses [ResponseBody.charStream], which honors the HTTP charset and skips a BOM.
 * Jsoup uses the Content-Type charset when present, otherwise detects BOM / HTML meta.
 */
object ResponseBodies {

    @WorkerThread
    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(body: ResponseBody, gson: Gson, type: Class<T>): T {
        try {
            body.use { owned ->
                JsonReader(owned.charStream()).use { reader ->
                    return gson.fromJson(reader, type)
                }
            }
        } catch (e: Exception) {
            generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<IOException>()
                .firstOrNull()
                ?.let { throw it }
            throw e
        }
    }

    @WorkerThread
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun parseHtml(body: ResponseBody, baseUri: String = ""): Document {
        body.use { owned ->
            val charsetName = owned.contentType()?.charset()?.name()
            return Jsoup.parse(owned.byteStream(), charsetName, baseUri)
        }
    }
}

@WorkerThread
@Throws(IOException::class)
fun <T> ResponseBody.fromJson(gson: Gson, type: Class<T>): T =
    ResponseBodies.fromJson(this, gson, type)

@WorkerThread
@Throws(IOException::class)
fun ResponseBody.parseHtml(baseUri: String = ""): Document =
    ResponseBodies.parseHtml(this, baseUri)
