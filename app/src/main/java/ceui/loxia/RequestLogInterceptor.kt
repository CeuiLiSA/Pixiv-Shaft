package ceui.loxia

import ceui.lisa.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * debug 包专用的请求可见性日志：**只回答「什么时候、往哪儿、发了什么方法的请求、结果如何」**。
 *
 * ## 为什么不是 HttpLoggingInterceptor
 *
 * 这个位置原本挂的就是它，级别 `BODY`。而 `BODY` 会把请求头一并打出来 —— 其中就有
 * `Authorization: Bearer <access token>`，还有整个响应体。logcat 不是私密的：一份
 * bug report、一个拿到 READ_LOGS 的应用，就足以把账号捡走。所以 5570dc5aa 把它连同
 * `logging-interceptor` 依赖一起删干净了，这一步是对的。
 *
 * 删过头的是另一半：debug 下连「**有没有发出去过请求**」都看不见了。排查限流、确认某个
 * 后台任务到底动没动、看某次操作打了几次网络，全都无从下手。这个类把那一半加回来，
 * 同时把泄密面压到零：
 *
 * - **只打方法 + URL + 状态码 + 耗时 + 响应体字节数**；
 * - **绝不碰任何 header**（token / cookie / csrf 全在那里）；
 * - **绝不读 body**（读了还要 peek 回去，既有内存代价又会把用户内容写进日志）。
 *
 * URL 本身对挂了本拦截器的这几个域是安全的：app-api / comic 的 query 是
 * user_id / restrict / max_bookmark_id 这类参数，token 一律走 header；网页 ajax 与
 * FANBOX 靠 cookie，也在 header 里。**OAuth 根本不经过这里**——登录与 token 刷新用的是
 * [ceui.pixiv.login.PixivLogin] 自己建的那个 OkHttpClient（`TokenFetcherInterceptor`
 * 撞 400 时也是转去调它），所以 refresh_token 连出现在本类视野里的机会都没有。
 *
 * ⚠️ 打的是**改写前**的 URL：开着 PxveAPI 代理时，`AppApiProxyInterceptor` 会把
 * app-api / oauth 重写到代理域名，而它排在本拦截器之后。所以日志里看到的永远是
 * 「这次业务请求要的是哪个 pixiv 接口」，不是「实际连了哪台主机」——排查代理本身时
 * 别拿它当依据。
 *
 * ## 只在 debug 装
 *
 * 由 [BuildConfig.DEBUG] 门控（见 [installOn]）。release 包连拦截器都不会挂上，
 * 没有任何运行时代价，也不依赖「release 没 plant Timber tree」这个间接保证。
 */
class RequestLogInterceptor(private val tag: String) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 失败同样要可见：断网 / 超时 / 连接重置在日志里必须留痕，否则「请求发了没」
            // 这个问题在失败路径上依然答不出来。
            Timber.tag(tag).w(
                "%s %s ✗ %s (%dms)",
                request.method, request.url, e.javaClass.simpleName, elapsedMs(startedAt),
            )
            throw e
        }
        // contentLength() 取的是响应头里的 Content-Length，不读流、不消费 body；
        // 分块传输时它是 -1，照实打出来即可。
        val bytes = response.body?.contentLength() ?: -1L
        Timber.tag(tag).i(
            "%s %s → %d (%dms%s)",
            request.method,
            request.url,
            response.code,
            elapsedMs(startedAt),
            if (bytes >= 0) ", ${bytes}B" else "",
        )
        return response
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        /** debug 才挂。release 上这行是个 no-op，拦截器链里根本不会多出这一环。 */
        fun installOn(builder: okhttp3.OkHttpClient.Builder, tag: String) {
            if (!BuildConfig.DEBUG) return
            builder.addInterceptor(RequestLogInterceptor(tag))
        }
    }
}
