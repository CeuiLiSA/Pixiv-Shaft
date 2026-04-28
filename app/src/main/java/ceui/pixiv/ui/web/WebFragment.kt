package ceui.pixiv.ui.web

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebBinding
import ceui.lisa.utils.Common
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.loxia.ClientManager
import ceui.loxia.CsrfTokenProvider
import com.scwang.smart.refresh.header.MaterialHeader
import com.tencent.mmkv.MMKV


class WebFragment : PixivFragment(R.layout.fragment_web) {

    private val args by lazy {
        WebFragmentArgs.fromBundle(requireArguments())
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
            Common.showLog("dsaadsdsaaww2 JsBridge csrf token=$token")
            CsrfTokenProvider.set(token)
        }

        @JavascriptInterface
        fun onDebug(msg: String) {
            Common.showLog("dsaadsdsaaww2 JsBridge debug: $msg")
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Check whether there's history.
            if (view != null) {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    back()
                }
            } else {
                back()
            }
        }
    }

    private fun back() {
        onBackPressedCallback.isEnabled = false
        try {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).popBackStack()
        } catch (e: IllegalStateException) {
            activity?.finish()
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
        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setEnableLoadMore(false)
        binding.refreshLayout.setOnRefreshListener { // 重新加载 WebView 页面
            binding.webView.reload()
        }

        val webSettings: WebSettings = binding.webView.settings
        webSettings.userAgentString = ClientManager.WEB_USER_AGENT
        val refreshLayout = binding.refreshLayout

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (args.saveCookies) {
                    // 始终从 www.pixiv.net 域取 cookie，确保拿到 PHPSESSID
                    val cookie = CookieManager.getInstance().getCookie("https://www.pixiv.net")
                    if (!cookie.isNullOrEmpty() && cookie.contains("PHPSESSID")) {
                        Common.showLog("dsaadsdsaaww2 set $cookie")
                        prefStore.putString(SessionManager.COOKIE_KEY, cookie)
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
                                        CsrfBridge.onDebug('found via pixiv.context.token');
                                        return;
                                    } else {
                                        CsrfBridge.onDebug('s1: pixiv.context=' + (window.pixiv ? JSON.stringify(Object.keys(window.pixiv)).substring(0,100) : 'undefined'));
                                    }
                                } catch(e) { CsrfBridge.onDebug('s1 error: ' + e); }

                                // 策略2: 从 globalInitData 读取
                                try {
                                    if (window.globalInitData && window.globalInitData.token) {
                                        CsrfBridge.onCsrfToken(window.globalInitData.token);
                                        CsrfBridge.onDebug('found via globalInitData.token');
                                        return;
                                    } else {
                                        CsrfBridge.onDebug('s2: globalInitData=' + (window.globalInitData ? 'exists,keys=' + JSON.stringify(Object.keys(window.globalInitData)).substring(0,100) : 'undefined'));
                                    }
                                } catch(e) { CsrfBridge.onDebug('s2 error: ' + e); }

                                // 策略3: 从 __NEXT_DATA__ JS 对象提取
                                try {
                                    if (window.__NEXT_DATA__) {
                                        var json = JSON.stringify(window.__NEXT_DATA__).replace(/\\"/g, '"');
                                        var m = json.match(/"token":"([a-f0-9]{32})"/);
                                        if (m) {
                                            CsrfBridge.onCsrfToken(m[1]);
                                            CsrfBridge.onDebug('found via __NEXT_DATA__');
                                            return;
                                        }
                                    }
                                } catch(e) { CsrfBridge.onDebug('s3 error: ' + e); }

                                // 策略4: 从 meta-global-data 标签提取
                                try {
                                    var meta = document.getElementById('meta-global-data');
                                    if (meta) {
                                        var c = meta.getAttribute('content');
                                        var m2 = c.match(/"token":"([a-f0-9]{32})"/);
                                        if (m2) {
                                            CsrfBridge.onCsrfToken(m2[1]);
                                            CsrfBridge.onDebug('found via meta-global-data');
                                            return;
                                        } else {
                                            var ti2 = c.indexOf('token');
                                            CsrfBridge.onDebug('s4: meta-global-data len=' + c.length + ', tokenAt=' + ti2 + (ti2>=0 ? ', near=' + c.substring(ti2,ti2+60) : ''));
                                        }
                                    } else {
                                        CsrfBridge.onDebug('s4: meta-global-data element not found');
                                    }
                                } catch(e) { CsrfBridge.onDebug('s4 error: ' + e); }

                                // 策略5: 从页面 HTML 搜索 token
                                try {
                                    var html = document.documentElement.innerHTML;
                                    var m3 = html.match(/"token":"([a-f0-9]{32})"/);
                                    if (m3) {
                                        CsrfBridge.onCsrfToken(m3[1]);
                                        CsrfBridge.onDebug('found via innerHTML');
                                        return;
                                    } else {
                                        var ti3 = html.indexOf('"token"');
                                        CsrfBridge.onDebug('s5: innerHTML len=' + html.length + ', tokenAt=' + ti3 + (ti3>=0 ? ', near=' + html.substring(ti3,ti3+80) : ''));
                                    }
                                } catch(e) { CsrfBridge.onDebug('s5 error: ' + e); }

                                // 策略6: fetch 首页 HTML
                                fetch(location.origin, {credentials: 'include'})
                                    .then(function(r){ return r.text(); })
                                    .then(function(data){
                                        var m = data.match(/"token":"([a-f0-9]+)"/);
                                        if(m) {
                                            CsrfBridge.onCsrfToken(m[1]);
                                            CsrfBridge.onDebug('found via fetch');
                                        } else {
                                            // 搜索 token 关键字位置
                                            var ti = data.indexOf('token');
                                            var tokenSnippet = ti >= 0 ? data.substring(Math.max(0,ti-20), ti+80) : 'token not found';
                                            // 搜索 meta-global-data
                                            var mi = data.indexOf('meta-global-data');
                                            var metaSnippet = mi >= 0 ? data.substring(mi, mi+200) : 'meta-global-data not found';
                                            CsrfBridge.onDebug('fetch no match, len=' + data.length + ', tokenAt=' + ti + ', tokenSnippet=' + tokenSnippet + ', metaSnippet=' + metaSnippet);
                                        }
                                    })
                                    .catch(function(e){ CsrfBridge.onDebug('fetch error: ' + e); });
                            })()
                            """.trimIndent(),
                            null
                        )
                    }
                }

                if (view != null) {
                    refreshLayout.finishRefresh()
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (view != null) {
                    refreshLayout.finishRefresh()
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val requestUrlString = request?.url?.toString()
                Common.showLog("asewsd requestUrlString ${requestUrlString}")
                return super.shouldInterceptRequest(view, request)
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
        requireActivity().onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback.isEnabled = false
    }
}