package ceui.pixiv.ui.web

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebBinding
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.viewBinding
import ceui.loxia.ClientManager
import ceui.loxia.CsrfTokenProvider
import ceui.pixiv.widgets.applyV3RefreshTheme
import com.tencent.mmkv.MMKV


class WebFragment : Fragment(R.layout.fragment_web) {

    private val args by lazy { WebArgs(requireArguments()) }

    private class WebArgs(b: Bundle) {
        val url: String = b.getString("url").orEmpty()
        val saveCookies: Boolean = b.getBoolean("save_cookies")
    }

    companion object {
        fun newInstance(url: String, saveCookies: Boolean = false): WebFragment {
            return WebFragment().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                    putBoolean("save_cookies", saveCookies)
                }
            }
        }
    }
    private val binding by viewBinding(FragmentWebBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    private inner class CsrfBridge {
        @JavascriptInterface
        fun onCsrfToken(token: String) {
            CsrfTokenProvider.set(token)
        }
    }

    /**
     * 返回键/手势先退网页历史。enabled 跟着 canGoBack 走(doUpdateVisitedHistory 里刷新):
     * 没有网页历史时不拦,系统自己 finish 宿主 TemplateActivity 并播预测式返回动画;
     * 常开 callback 会把这页的预测式返回整个掐掉。
     */
    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (view != null && binding.webView.canGoBack()) {
                binding.webView.goBack()
                return
            }
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.refreshLayout.updateLayoutParams<MarginLayoutParams> {
                topMargin = insets.top
            }
            WindowInsetsCompat.CONSUMED
        }

        // 设置 SwipeRefreshLayout 的刷新监听器
        binding.refreshLayout.applyV3RefreshTheme()
        binding.refreshLayout.setOnRefreshListener { // 重新加载 WebView 页面
            binding.webView.reload()
        }

        val webSettings: WebSettings = binding.webView.settings
        webSettings.userAgentString = ClientManager.WEB_USER_AGENT
        val refreshLayout = binding.refreshLayout

        binding.webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onBackPressedCallback.isEnabled = view?.canGoBack() == true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onBackPressedCallback.isEnabled = view?.canGoBack() == true
                if (args.saveCookies) {
                    // 始终从 www.pixiv.net 域取 cookie，确保拿到 PHPSESSID。只认已登录的那种
                    // （<uid>_<hash>）——匿名 PHPSESSID 存下去会把 hasWebCookie 骗成真。
                    val cookie = CookieManager.getInstance().getCookie("https://www.pixiv.net")
                    if (SessionManager.isLoggedInWebCookie(cookie)) {
                        prefStore.putString(SessionManager.COOKIE_KEY, SessionManager.normalizeWebCookie(cookie))
                    }
                    // 在 pixiv 页面提取 CSRF token
                    if (url?.contains("www.pixiv.net") == true && view != null) {
                        view.evaluateJavascript(
                            """
                            (function(){
                                // 策略1: 直接读取 pixiv 全局 JS 变量
                                try {
                                    if (window.pixiv && window.pixiv.context && window.pixiv.context.token) {
                                        CsrfBridge.onCsrfToken(window.pixiv.context.token);
                                        return;
                                    }
                                } catch(e) {}

                                // 策略2: 从 globalInitData 读取
                                try {
                                    if (window.globalInitData && window.globalInitData.token) {
                                        CsrfBridge.onCsrfToken(window.globalInitData.token);
                                        return;
                                    }
                                } catch(e) {}

                                // 策略3: 从 __NEXT_DATA__ JS 对象提取
                                try {
                                    if (window.__NEXT_DATA__) {
                                        var json = JSON.stringify(window.__NEXT_DATA__).replace(/\\"/g, '"');
                                        var m = json.match(/"token":"([a-f0-9]{32})"/);
                                        if (m) {
                                            CsrfBridge.onCsrfToken(m[1]);
                                            return;
                                        }
                                    }
                                } catch(e) {}

                                // 策略4: 从 meta-global-data 标签提取
                                try {
                                    var meta = document.getElementById('meta-global-data');
                                    if (meta) {
                                        var c = meta.getAttribute('content');
                                        var m2 = c.match(/"token":"([a-f0-9]{32})"/);
                                        if (m2) {
                                            CsrfBridge.onCsrfToken(m2[1]);
                                            return;
                                        }
                                    }
                                } catch(e) {}

                                // 策略5: 从页面 HTML 搜索 token
                                try {
                                    var html = document.documentElement.innerHTML;
                                    var m3 = html.match(/"token":"([a-f0-9]{32})"/);
                                    if (m3) {
                                        CsrfBridge.onCsrfToken(m3[1]);
                                        return;
                                    }
                                } catch(e) {}

                                // 策略6: fetch 首页 HTML
                                fetch(location.origin, {credentials: 'include'})
                                    .then(function(r){ return r.text(); })
                                    .then(function(data){
                                        var m = data.match(/"token":"([a-f0-9]+)"/);
                                        if(m) {
                                            CsrfBridge.onCsrfToken(m[1]);
                                        }
                                    })
                                    .catch(function(e){});
                            })()
                            """.trimIndent(),
                            null
                        )
                    }
                }

                if (view != null) {
                    refreshLayout.isRefreshing = false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (view != null) {
                    refreshLayout.isRefreshing = false
                }
            }

        }
        binding.webView.webChromeClient = object : WebChromeClient() {

        }
        // UI
        webSettings.useWideViewPort = true //-> 缩放至屏幕大小
        webSettings.loadWithOverviewMode = true// -> 缩放至屏幕大小
        webSettings.setSupportZoom(true) //-> 是否支持缩放
        webSettings.builtInZoomControls = true// -> 是否支持缩放变焦，前提是支持缩放
        webSettings.displayZoomControls = false //-> 是否隐藏缩放控件

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true// -> 是否节点缓存

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        if (args.saveCookies) {
            // 登录流程：清除旧 cookie，确保干净的登录状态
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } else {
            // 非登录流程：注入已同步的 Cookie，确保需要登录的页面能正常加载
            val savedCookies = prefStore.getString(SessionManager.COOKIE_KEY, "")
            if (!savedCookies.isNullOrEmpty()) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                for (cookie in savedCookies.split(";")) {
                    cookieManager.setCookie(args.url, cookie.trim())
                }
                cookieManager.flush()
            }
        }

        // 注册 JS Bridge 用于接收 CSRF token
        binding.webView.addJavascriptInterface(CsrfBridge(), "CsrfBridge")

        // 加载 URL
        binding.webView.loadUrl(args.url)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
    }
}
