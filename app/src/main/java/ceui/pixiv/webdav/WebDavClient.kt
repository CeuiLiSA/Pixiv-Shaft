package ceui.pixiv.webdav

import android.net.Uri
import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** WebDAV 请求失败。[kind] 决定给用户看哪句提示，[code] 是 HTTP 状态码（无响应时为 0）。 */
class WebDavException(val kind: Kind, val code: Int, message: String) : IOException(message) {
    enum class Kind { BAD_URL, AUTH, NOT_FOUND, HTTP }
}

/** 远端目录里的一个文件。 */
data class WebDavEntry(val name: String, val size: Long)

/**
 * 只覆盖备份所需的最小 WebDAV 子集：PROPFIND（Depth 0/1）、MKCOL、PUT、GET、DELETE。
 * 全部是阻塞调用，必须在 IO 线程使用。
 *
 * 认证用抢先发送的 Basic：坚果云 / Nextcloud / pCloud / InfiniCloud 等都支持，省一次 401
 * 往返；跨主机重定向时 OkHttp 会自己丢掉 Authorization，不会把密码带给别的域名。
 */
class WebDavClient(config: WebDavConfig) {

    private val authorization = Credentials.basic(config.username, config.password, Charsets.UTF_8)

    private val baseUrl: HttpUrl = config.baseUrl.toHttpUrlOrNull()
        ?: throw WebDavException(WebDavException.Kind.BAD_URL, 0, "invalid url: ${config.baseUrl}")

    private val folderSegments: List<String> = WebDavConfig.normalizeFolder(config.folder)
        .split('/').filter { it.isNotEmpty() }

    /** 根地址可达、账号密码正确。 */
    fun checkAccess() {
        propfind(baseUrl, depth = 0).use { response ->
            response.requireSuccess()
            val body = response.body ?: throw IOException("empty WebDAV response")
            parseMultistatus(body.byteStream())
        }
    }

    /** 逐级 MKCOL 建出备份目录；已存在（405）视为成功。 */
    fun ensureFolder() {
        for (i in folderSegments.indices) {
            val url = dirUrl(folderSegments.subList(0, i + 1))
            val request = newRequest(url).method("MKCOL", null).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 405) response.requireSuccess()
            }
        }
    }

    /** 备份目录下的文件（不含子目录）。目录不存在返回空表。 */
    fun listFiles(): List<WebDavEntry> {
        propfind(dirUrl(folderSegments), depth = 1).use { response ->
            if (response.code == 404) return emptyList()
            response.requireSuccess()
            val body = response.body ?: throw IOException("empty WebDAV response")
            return parseMultistatus(body.byteStream())
        }
    }

    fun upload(name: String, file: File, contentType: String) {
        val request = newRequest(fileUrl(name))
            .put(file.asRequestBody(contentType.toMediaType()))
            .build()
        http.newCall(request).execute().use { it.requireSuccess() }
    }

    fun download(name: String, target: File) {
        val request = newRequest(fileUrl(name)).get().build()
        http.newCall(request).execute().use { response ->
            response.requireSuccess()
            val body = response.body ?: throw WebDavException(WebDavException.Kind.HTTP, response.code, "empty body")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    fun delete(name: String) {
        val request = newRequest(fileUrl(name)).delete().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) response.requireSuccess()
        }
    }

    private fun propfind(url: HttpUrl, depth: Int): Response {
        val request = newRequest(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute()
    }

    private fun newRequest(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", authorization)

    /** 目录 URL 一律带结尾 `/`。 */
    private fun dirUrl(segments: List<String>): HttpUrl =
        baseUrl.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            addPathSegment("")
        }.build()

    private fun fileUrl(name: String): HttpUrl =
        baseUrl.newBuilder().apply {
            folderSegments.forEach { addPathSegment(it) }
            addPathSegment(name)
        }.build()

    private fun Response.requireSuccess() {
        if (isSuccessful) return
        val kind = when (code) {
            401, 403 -> WebDavException.Kind.AUTH
            404, 409 -> WebDavException.Kind.NOT_FOUND
            else -> WebDavException.Kind.HTTP
        }
        throw WebDavException(kind, code, "HTTP $code ${message}".trim())
    }

    /**
     * 解析 207 Multi-Status，只取文件（resourcetype 里没有 collection 的 response）。
     * 按 local name 匹配、忽略命名空间前缀：各家服务器 `d:` / `D:` / 默认命名空间写法都有。
     */
    private fun parseMultistatus(input: InputStream): List<WebDavEntry> {
        try {
            return readMultistatus(input)
        } catch (e: XmlPullParserException) {
            throw IOException("invalid WebDAV response", e)
        }
    }

    private fun readMultistatus(input: InputStream): List<WebDavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "multistatus" || parser.namespace != "DAV:") {
            throw IOException("not a WebDAV multistatus response")
        }
        val result = mutableListOf<WebDavEntry>()
        var href: String? = null
        var size = 0L
        var isCollection = false
        var complete = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        href = null
                        size = 0L
                        isCollection = false
                    }
                    "href" -> href = parser.nextText().trim()
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "collection" -> isCollection = true
                }
                XmlPullParser.END_TAG -> when {
                    parser.depth == 1 && parser.name == "multistatus" -> complete = true
                    parser.name == "response" -> {
                        val name = href?.let(::lastSegment)
                        if (!isCollection && !name.isNullOrEmpty()) result += WebDavEntry(name, size)
                    }
                }
            }
        }
        if (!complete) throw IOException("incomplete WebDAV multistatus response")
        return result
    }

    /** href 可能是绝对 URL 或路径，且是百分号编码的。 */
    private fun lastSegment(href: String): String =
        Uri.decode(href.trimEnd('/').substringAfterLast('/'))

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

        /** 读写超时按「两次数据之间的空闲」计，大备份上传也不会被总时长卡死。 */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
