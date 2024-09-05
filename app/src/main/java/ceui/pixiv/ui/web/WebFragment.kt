package ceui.pixiv.ui.web

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpToolbar
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class WebFragment : PixivFragment(R.layout.fragment_web) {

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentWebBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.webView.updatePadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val webSettings: WebSettings = binding.webView.settings

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    val cookie = CookieManager.getInstance().getCookie(it)
                    Common.showLog("dsaadsdsaaww2 set ${cookie}")
                    prefStore.putString("web-api-cookie", cookie)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val requestUrlString = request?.url?.toString()
                if (requestUrlString?.contains("ajax/user/bookmarks") == true) {
                    val headersMap: Map<String, String> = request.requestHeaders
                    val jsonHeaders: String = Gson().toJson(headersMap)
                    prefStore.putString("web-api-header", jsonHeaders)
                    Common.showLog("asewsd requestUrlString ${requestUrlString}")
                    Common.showLog("asewsd jsonHeaders ${jsonHeaders}")
                }
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

        // 加载 URL
        binding.webView.loadUrl("https://www.pixiv.net")
    }
}