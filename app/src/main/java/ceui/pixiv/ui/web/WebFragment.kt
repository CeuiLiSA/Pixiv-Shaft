package ceui.pixiv.ui.web

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebBinding
import ceui.lisa.utils.Common
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import com.scwang.smart.refresh.header.MaterialHeader
import com.tencent.mmkv.MMKV
import androidx.core.net.toUri
import androidx.navigation.NavOptions
import ceui.lisa.feature.HostManager
import ceui.lisa.fragments.FragmentLogin
import ceui.loxia.AccountResponse
import ceui.loxia.Client
import ceui.loxia.launchSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Callback
import retrofit2.Call
import retrofit2.Response
import timber.log.Timber


class WebFragment : PixivFragment(R.layout.fragment_web) {

    private val args by navArgs<WebFragmentArgs>()
    private val binding by viewBinding(FragmentWebBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("shaft-session")
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
        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setEnableLoadMore(false)
        binding.refreshLayout.setOnRefreshListener { // 重新加载 WebView 页面
            binding.webView.reload()
        }

        val webSettings: WebSettings = binding.webView.settings
        val refreshLayout = binding.refreshLayout

        val navController = findNavController()
        val linkHandler = LinkHandler(navController)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (args.saveCookies) {
                    url?.let {
                        val cookie = CookieManager.getInstance().getCookie(it)
                        Common.showLog("dsaadsdsaaww2 set ${cookie}")
                        prefStore.putString(SessionManager.COOKIE_KEY, cookie)
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

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val requestUrlString = request?.url?.toString()
                if (linkHandler.processLink(requestUrlString)) {
                    return true // 自己处理了
                }

                return super.shouldOverrideUrlLoading(view, request)
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

        // 加载 URL
        binding.webView.loadUrl(args.url)
        requireActivity().onBackPressedDispatcher.addCallback(onBackPressedCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback.isEnabled = false
    }
}