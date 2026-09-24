package ceui.pixiv.ui.web

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig

/**
 * 关掉 WebView 的 Media Integrity API(页面 JS 里的 `android.webview.getExperimentalMediaIntegrityProvider`)。
 * **本 app 创建的每个 WebView 都要调**,新增 WebView 时别漏。
 *
 * 为什么要关:老版 System WebView(M132 及以前,crbug 377472509)的 `AwMediaIntegrityServiceImpl`
 * 在处理页面发来的这条 mojo 请求时去拿 `AwContents.getBrowserContext()`,而那个方法对已 destroy 的
 * WebView 直接抛 `IllegalStateException: Cannot get profile for destroyed WebView.`。
 * 页面刚发出请求、我们这边恰好 destroy 了 WebView,消息晚一步到,整个进程就崩了。
 * 这条异常经 Chromium 的 JniAndroid 直接交给默认 UncaughtExceptionHandler,随后 native 侧自己 abort,
 * Shaft 里重入 Looper 的那套兜底接不住它。
 *
 * 那版代码在拿 profile **之前**先查 app 是否关了这个 API,关了就直接回 API_DISABLED_BY_APPLICATION,
 * 不再碰 profile。我们的页面(登录、图搜、FANBOX、公告)都不依赖这个 API,关掉没有代价。
 * 设备的 WebView 太老、不认这个开关时什么也不做。
 */
fun WebSettings.disableMediaIntegrityApi() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
        WebSettingsCompat.setWebViewMediaIntegrityApiStatus(
            this,
            WebViewMediaIntegrityApiStatusConfig.Builder(
                WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_DISABLED
            ).build()
        )
    }
}
