package ceui.loxia

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import ceui.lisa.BuildConfig
import ceui.lisa.utils.Common
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 用一个不上屏的 WebView 去打 api.fanbox.cc —— 专门为 `post.info` 存在。
 *
 * FANBOX 从 2026 年 4 月起给 `post.info` / `post.getEditable` 单独挂了一条 Cloudflare 规则:
 * 非浏览器客户端一律吃 403 + 一张 `ブロックされました` 的 HTML 拦截页(不是 JSON 错误,
 * 也不带 `Access-Control-Allow-Origin`)。同一份 cookie / UA / Origin 下 `post.get`、
 * `post.listHome` 稳定 200,可见判的不是登录态而是客户端本身:实测 curl 全 403、
 * headless Chrome 也全 403(UA 里的 `HeadlessChrome` 藏不住),而真机 Chrome 和本 app 的
 * WebView 都能正常拿到 —— 连 `cf_clearance` 都不需要。
 *
 * 所以 OkHttp 那条路走不通,正文只能从 WebView 里发出去。做法是加载一次
 * `https://www.fanbox.cc/` 拿到正经 origin(顺带让 CF 自己种它想种的 cookie),之后所有
 * 请求都在这张页面里 `fetch`,结果经 JS bridge 回传。cookie 直接用 [CookieManager] 里那份,
 * 和 [FanboxHeaderInterceptor] 同源,不存在两份登录态。
 *
 * 首屏那 5MB SPA(全在 s.pximg.net 上)一点用都没有,`shouldInterceptRequest` 把 fanbox.cc
 * 以外的子资源全掐掉,实际只下 8KB 左右的壳。
 *
 * 生命周期:进程里只有一份,由 [ceui.lisa.activities.Shaft] 构造并经
 * [ServicesProvider.fanboxWebBridge] 暴露。构造不建 WebView;第一次 [get] 才懒建,
 * 空闲 [IDLE_RELEASE_MS] 后或 Application.onTrimMemory 时经 [release] 销毁,下次再用重建。
 * WebView 是全 app 最重的对象之一(几十 MB 的独立渲染进程),不能像以前那样建了就常驻。
 */
class FanboxWebBridge(app: Context) {

    private val appContext: Context = app.applicationContext

    private val requestMutex = Mutex()
    private val sequence = AtomicLong(0L)
    private val pending = ConcurrentHashMap<String, CancellableContinuation<String?>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable { release() }

    /** 只在主线程碰。null = 还没建过、上一次建失败(WebView 组件缺失的设备)或已被 [release]。 */
    private var webView: WebView? = null
    private var pageLoaded = false
    private var pageWaiter: CancellableContinuation<Unit>? = null

    /**
     * GET 一个 api.fanbox.cc 上的接口,返回响应体;非 200(含 CF 拦截页)或超时一律 null。
     * 调用方自己决定要不要退回 OkHttp 那套只读元数据的接口。
     */
    suspend fun get(url: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        requestMutex.withLock {
            withContext(Dispatchers.Main) {
                mainHandler.removeCallbacks(idleRelease)
                try {
                    val view = ensureWebView() ?: return@withContext null
                    if (!ensurePageLoaded(view)) return@withContext null
                    fetchInPage(view, url)
                } finally {
                    // 无论成败都重新起倒计时;下一次 get 进来会先取消它。
                    mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
                }
            }
        }
    }

    /**
     * 销毁 WebView、放掉所有还在等的调用方(以 null 结束,调用方走 post.get 兜底)。
     * 可重复调用;释放后下一次 [get] 会重新懒建并重新加载首页壳。
     */
    @MainThread
    fun release() {
        mainHandler.removeCallbacks(idleRelease)
        pageWaiter?.let { pageWaiter = null; if (it.isActive) it.resume(Unit) }
        pending.keys.toList().forEach { token ->
            pending.remove(token)?.let { if (it.isActive) it.resume(null) }
        }
        pageLoaded = false
        val view = webView ?: return
        webView = null
        // 先摘 bridge 再 destroy:destroy 之后 JS 仍可能回调一次,别让它碰到已经清空的表。
        view.removeJavascriptInterface(BRIDGE_NAME)
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.destroy()
        Common.showLog("FanboxWebBridge released")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        // WebView 在少数机型上会因为组件正在升级而直接抛,这里不能让 FANBOX 详情页跟着崩。
        val view = runCatching { WebView(appContext) }.getOrElse {
            Common.showLog("FanboxWebBridge WebView 不可用: $it")
            return null
        }
        if (BuildConfig.DEBUG) {
            // 这张页面不上屏,出问题时只能靠 chrome://inspect 连进去看 —— 正文链路整个
            // 依赖 CF 认不认这个 WebView,没有 devtools 基本没法查。
            WebView.setWebContentsDebuggingEnabled(true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // UA 保持 WebView 自己那份,**别换成 ClientManager.WEB_USER_AGENT** ——
            // 那个常量钉死在 Chrome/131,而设备上的 WebView 是另一个版本,声称的版本号和真实
            // TLS/HTTP2 指纹对不上正是 CF 判机器人的典型信号。这条通不通没单独验过,
            // 但既然默认 UA 就能过,没有任何理由去冒这个险。
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.addJavascriptInterface(JsBridge(), BRIDGE_NAME)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 断网时 WebView 也会走到 onPageFinished(渲染的是错误页),那种页面的 origin
                // 不是 fanbox.cc,拿它当就绪会让后面每次 fetch 都吃 CORS。认 URL 才算数。
                pageLoaded = url.orEmpty().startsWith(ORIGIN)
                pageWaiter?.let { pageWaiter = null; it.resume(Unit) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 主文档挂了就别让调用方干等 25 秒超时,立刻放行走 post.get 兜底。
                if (request?.isForMainFrame == true) {
                    pageWaiter?.let { pageWaiter = null; it.resume(Unit) }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val host = request?.url?.host
                // 放行整个 fanbox.cc —— **api.fanbox.cc 也在里面**,它才是这张页面存在的理由,
                // 掐掉的话 fetch 拿到的是本地伪造的空响应,报出来是 CORS 错误,跟被 CF 挡了
                // 一模一样,极难分辨。其余全掐:s.pximg.net 上那几 MB 的 SPA、GA、
                // Twitter widget 一个都用不上。
                if (host != null && host != FANBOX_DOMAIN && !host.endsWith(".$FANBOX_DOMAIN")) {
                    return EMPTY_RESPONSE
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        webView = view
        return view
    }

    /** 首次调用会真的去加载一次首页壳;之后复用同一张页面。加载失败返回 false。 */
    private suspend fun ensurePageLoaded(view: WebView): Boolean {
        if (pageLoaded) return true
        suspendCancellableCoroutine { cont ->
            pageWaiter = cont
            cont.invokeOnCancellation { pageWaiter = null }
            view.loadUrl(ORIGIN)
        }
        return pageLoaded
    }

    private suspend fun fetchInPage(view: WebView, url: String): String? {
        val token = "fb-${sequence.incrementAndGet()}"
        return suspendCancellableCoroutine { cont ->
            pending[token] = cont
            cont.invokeOnCancellation { pending.remove(token) }
            view.evaluateJavascript(fetchScript(token, url), null)
        }
    }

    private fun fetchScript(token: String, url: String): String {
        val safeUrl = url.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function(){
                var t = '$token';
                fetch('$safeUrl', { credentials: 'include', headers: { 'Accept': 'application/json' } })
                    .then(function(r){
                        return r.text().then(function(body){ $BRIDGE_NAME.onResult(t, r.status, body); });
                    })
                    .catch(function(e){ $BRIDGE_NAME.onResult(t, -1, String(e)); });
            })();
        """.trimIndent()
    }

    /** 非 static 内部类:JS 回调要落回这一个 bridge 实例的 pending 表。 */
    private inner class JsBridge {
        @JavascriptInterface
        fun onResult(token: String, status: Int, body: String) {
            val cont = pending.remove(token) ?: return
            if (status != 200) {
                Common.showLog("FanboxWebBridge $token 失败 status=$status")
            }
            if (cont.isActive) cont.resume(body.takeIf { status == 200 })
        }
    }

    private companion object {
        const val ORIGIN = "https://www.fanbox.cc/"
        const val FANBOX_DOMAIN = "fanbox.cc"
        const val BRIDGE_NAME = "FanboxNativeBridge"
        const val TIMEOUT_MS = 25_000L

        /**
         * 空闲多久后销毁 WebView。用户在 FANBOX 里连点几篇帖子时,每篇都重建 WebView +
         * 重载首页壳(一次网络往返 + CF 种 cookie)太浪费;但读完正文离开 FANBOX 之后这张
         * 页面就没有任何用处了。60 秒够覆盖「看完一篇回列表点下一篇」的间隔,又不会让
         * 一个渲染进程在后台白占几十 MB。
         */
        const val IDLE_RELEASE_MS = 60_000L

        /**
         * 掐掉子资源用的空响应。给 200 而不是错误码 —— WebView 对被拦下的资源不会报错,
         * 但个别机型上返回 null body 会打一串 console 噪音。
         */
        val EMPTY_RESPONSE: WebResourceResponse
            get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
