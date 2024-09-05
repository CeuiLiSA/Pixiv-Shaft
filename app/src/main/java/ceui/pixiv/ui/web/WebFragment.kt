package ceui.pixiv.ui.web

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.PixivFragment
import com.tencent.mmkv.MMKV

class WebFragment : PixivFragment(R.layout.fragment_web) {

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentWebBinding.bind(view)

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
        }
        binding.webView.webChromeClient = object : WebChromeClient() {

        }
        webSettings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
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