package ceui.pixiv.download.aria2

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 极简 aria2 JSON-RPC 客户端（#692 远程下载到 NAS）。
 *
 * 只实现 Shaft 用到的两个方法：
 *  - `aria2.addUri`     把一条下载任务交给远端 aria2
 *  - `aria2.getVersion` 设置页「测试连接」用
 *
 * 网络栈跟 pixiv 完全独立 —— RPC 端点通常在局域网（NAS），不能走
 * Cronet / direct-connect DNS / pixiv Header 拦截器，所以用独立的轻量
 * OkHttpClient（短超时，失败尽快反馈）。
 *
 * 所有方法都是同步阻塞调用，必须在 IO 线程执行。
 */
class Aria2Client(
    private val httpClient: OkHttpClient = sharedClient,
) {

    /**
     * aria2.addUri — 添加一条下载任务，返回 aria2 任务 GID。
     *
     * @param rpcUrl  JSON-RPC 端点，如 `http://192.168.1.5:6800/jsonrpc`
     * @param secret  RPC 密钥（aria2 的 --rpc-secret），空串 = 无密钥
     * @param fileUrl 待下载的文件 URL
     * @param out     保存的相对路径（可含子目录，相对于 aria2 的 dir）
     * @param dir     远端下载目录，空串 = 用 aria2 全局配置
     * @param headers 额外请求头（"Name: value" 形式）；pixiv 图片必须带 Referer 否则 403
     */
    @Throws(IOException::class)
    fun addUri(
        rpcUrl: String,
        secret: String,
        fileUrl: String,
        out: String,
        dir: String,
        headers: List<String>,
    ): String {
        val options = JsonObject().apply {
            addProperty("out", out)
            if (dir.isNotEmpty()) {
                addProperty("dir", dir)
            }
            add("header", JsonArray().apply { headers.forEach { add(it) } })
        }
        val params = JsonArray().apply {
            if (secret.isNotEmpty()) {
                add("token:$secret")
            }
            add(JsonArray().apply { add(fileUrl) })
            add(options)
        }
        return call(rpcUrl, "aria2.addUri", params).asString
    }

    /** aria2.getVersion — 返回 aria2 版本号，「测试连接」用。 */
    @Throws(IOException::class)
    fun getVersion(rpcUrl: String, secret: String): String {
        val params = JsonArray().apply {
            if (secret.isNotEmpty()) {
                add("token:$secret")
            }
        }
        val result = call(rpcUrl, "aria2.getVersion", params)
        return result.asJsonObject.get("version")?.asString.orEmpty()
    }

    /**
     * 发一次 JSON-RPC 调用并返回 result 字段。
     *
     * aria2 的 RPC 错误（密钥不对 / 参数非法）是 4xx + JSON error 结构，
     * 优先把 error.message 透出去 —— 比裸的 "HTTP 401" 对用户有用得多。
     */
    private fun call(rpcUrl: String, method: String, params: JsonArray): JsonElement {
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "shaft")
            addProperty("method", method)
            add("params", params)
        }
        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            parseRpcError(bodyText)?.let { throw IOException(it) }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val root = try {
                JsonParser.parseString(bodyText).asJsonObject
            } catch (e: Exception) {
                // 反向代理吐 HTML / 端点不是 aria2 之类
                throw IOException("aria2: unexpected response")
            }
            return root.get("result") ?: throw IOException("aria2: response has no result")
        }
    }

    private fun parseRpcError(bodyText: String): String? {
        if (bodyText.isEmpty()) return null
        return try {
            val root = JsonParser.parseString(bodyText).asJsonObject
            val error = root.getAsJsonObject("error") ?: return null
            error.get("message")?.asString ?: "aria2 error code ${error.get("code")?.asInt}"
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
