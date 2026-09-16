package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/** 将当前 Android 运行时的 HTTP UA 交给服务端生成翻译 client_key。 */
internal class TranslateUserAgentInterceptor(
    private val userAgent: () -> String? = { System.getProperty("http.agent") },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (path != "/v1/config" && path != "/v1/account/translate" && path != "/v1/account/translate/quota") {
            return chain.proceed(original)
        }
        // v3 由服务端按有效会员档位选择 GPT/腾讯；配置、用量和翻译使用同一声明。
        // 旧 APK 的无版本/v2 路由保持不变，请求体与 HMAC 签名也不变。
        val request = original.newBuilder().header("X-Shaft-Translate-Version", "3").build()
        if (path != "/v1/account/translate") return chain.proceed(request)
        // 系统没有提供 UA 时使用本应用身份，不伪造浏览器或设备版本。
        val agent = userAgent()?.takeIf { it.isNotBlank() }
            ?: "PixShaft/${BuildConfig.VERSION_NAME}"
        // 厂商/自定义 ROM 的型号可能包含非 ASCII 字符；直接写 header 会让 OkHttp 抛异常。
        // 保留原字符的转义表示，服务端用实际发送的同一份 UA 生成 client_key。
        val headerAgent = buildString(agent.length) {
            for (char in agent) {
                if (char in ' '..'~') append(char)
                else append("\\u").append(char.code.toString(16).padStart(4, '0'))
            }
        }
        return chain.proceed(request.newBuilder().header("User-Agent", headerAgent).build())
    }
}
