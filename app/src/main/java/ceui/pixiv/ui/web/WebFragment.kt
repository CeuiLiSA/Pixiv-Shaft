package ceui.pixiv.ui.web

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.CookieManager
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
import androidx.core.view.updatePadding
import androidx.navigation.fragment.navArgs
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.FragmentWebBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpToolbar
import ceui.refactor.viewBinding
import com.google.gson.Gson
import com.tencent.mmkv.MMKV


class WebFragment : PixivFragment(R.layout.fragment_web) {

    private val args by navArgs<WebFragmentArgs>()
    private val binding by viewBinding(FragmentWebBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Check whether there's history.
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                back()
            }
        }
    }

    private fun back() {
        onBackPressedCallback.isEnabled = false
        findNavController().popBackStack()
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
        binding.refreshLayout.setOnRefreshListener { // 重新加载 WebView 页面
            binding.webView.reload()
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

                binding.refreshLayout.isRefreshing = false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.refreshLayout.isRefreshing = true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val requestUrlString = request?.url?.toString()
                if (requestUrlString?.contains("ajax") == true) {
//                    val headersMap: Map<String, String> = request.requestHeaders
//                    val jsonHeaders: String = Gson().toJson(headersMap)
//                    prefStore.putString("web-api-header", jsonHeaders)
                    Common.showLog("asewsd requestUrlString ${requestUrlString}")

                    // https://www.pixiv.net/touch/ajax/user/31660292/illusts/bookmarks?rest=show&offset=0&limit=48&lang=zh&version=ebdc1282e55d2c6d71244b71f158c2f32e968753
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
        binding.webView.loadUrl(args.url)
        requireActivity().onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback.isEnabled = false
    }
}