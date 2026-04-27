package ceui.lisa.fragments

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentBaseListBinding
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import ceui.lisa.databinding.ItemStreetContentBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.StreetContent
import ceui.loxia.StreetThumbnail
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

class StreetMainFragment : SwipeFragment<FragmentBaseListBinding>() {

    private val viewModel: StreetMainViewModel by viewModels()
    private val adapter = StreetAdapter()

    override fun initLayout() {
        mLayoutID = R.layout.fragment_base_list
    }

    override fun initView() {
        baseBind.toolbar.setNavigationOnClickListener { activity?.finish() }
        baseBind.toolbarTitle.text = getString(R.string.street_title)
        baseBind.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        baseBind.recyclerView.adapter = adapter

        baseBind.refreshLayout.setOnRefreshListener { viewModel.refresh() }
        baseBind.refreshLayout.setOnLoadMoreListener { viewModel.loadMore() }

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is StreetMainViewModel.LoadState.Refreshed -> {
                    adapter.notifyDataSetChanged()
                    baseBind.refreshLayout.finishRefresh()
                    baseBind.refreshLayout.setNoMoreData(!viewModel.hasMore)
                }
                is StreetMainViewModel.LoadState.LoadedMore -> {
                    adapter.notifyItemRangeInserted(state.insertStart, state.insertCount)
                    baseBind.refreshLayout.finishLoadMore()
                    baseBind.refreshLayout.setNoMoreData(!viewModel.hasMore)
                }
                is StreetMainViewModel.LoadState.Error -> {
                    baseBind.refreshLayout.finishRefresh(false)
                    baseBind.refreshLayout.finishLoadMore(false)
                    Toast.makeText(mContext, state.message, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout = baseBind.refreshLayout

    private var loginWebView: WebView? = null

    override fun lazyData() {
        val cookies = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "")
        if (cookies.isNullOrEmpty() || !cookies.contains("PHPSESSID")) {
            showWebLoginDialog()
        } else if (CsrfTokenProvider.get() == null) {
            // 有 cookie 但没 CSRF token，用 WebView 静默提取
            fetchCsrfViaWebView()
        } else {
            viewModel.refresh()
        }
    }

    /**
     * Cookie 已有，但 CSRF token 缺失。用隐藏 WebView 加载 pixiv.net，
     * 通过拦截 HTTP 响应从原始 HTML 里提取 token。
     */
    private fun fetchCsrfViaWebView() {
        Timber.d("StreetMain: have cookie but no CSRF, fetching via WebView")
        baseBind.toolbarTitle.text = getString(R.string.street_title)

        val webView = WebView(mContext).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.42 Mobile Safari/537.36"
        }
        loginWebView = webView

        val cookies = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "") ?: ""
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        for (c in cookies.split(";")) {
            cm.setCookie("https://www.pixiv.net", c.trim())
        }
        cm.flush()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // 只拦截主页面请求
                if (url == "https://www.pixiv.net/" || url == "https://www.pixiv.net") {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("Cookie", cookies)
                        conn.setRequestProperty("User-Agent", view?.settings?.userAgentString ?: "")
                        conn.setRequestProperty("Accept", "text/html")
                        conn.connect()
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()

                        val tokenRegex = Regex(""""token"\s*:\s*"([a-f0-9]{32})"""")
                        val token = tokenRegex.find(body)?.groupValues?.get(1)
                        Timber.d("StreetMain: intercepted HTML length=${body.length}, token=${token?.take(8)}")
                        if (token != null) {
                            MMKV.defaultMMKV().encode("web-api-csrf-token", token)
                        }

                        // 返回原始响应给 WebView
                        return android.webkit.WebResourceResponse(
                            "text/html", "utf-8",
                            body.byteInputStream(),
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "StreetMain: intercept failed")
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 无论 token 有没有拿到，页面加载完就结束
                if (url?.contains("www.pixiv.net") == true) {
                    cleanupWebView()
                    if (CsrfTokenProvider.get() != null) {
                        viewModel.refresh()
                    } else {
                        Toast.makeText(mContext, "无法获取 CSRF token，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        baseBind.refreshLayout.addView(webView)
        webView.loadUrl("https://www.pixiv.net/")
    }

    private fun showWebLoginDialog() {
        QMUIDialog.MessageDialogBuilder(mActivity)
            .setTitle(getString(R.string.street_web_login_title))
            .setMessage(getString(R.string.street_web_login_message))
            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
            .addAction(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                activity?.finish()
            }
            .addAction(getString(R.string.street_web_login_confirm)) { dialog, _ ->
                dialog.dismiss()
                startWebLogin()
            }
            .create()
            .show()
    }

    private fun startWebLogin() {
        baseBind.toolbarTitle.text = getString(R.string.street_web_login_toolbar)
        baseBind.recyclerView.visibility = View.GONE

        val webView = WebView(mContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.42 Mobile Safari/537.36"
        }
        loginWebView = webView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (!cookieSaved) return null
                val url = request?.url?.toString() ?: return null
                if (url == "https://www.pixiv.net/" || url == "https://www.pixiv.net") {
                    try {
                        val savedCookie = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "") ?: ""
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("Cookie", savedCookie)
                        conn.setRequestProperty("User-Agent", view?.settings?.userAgentString ?: "")
                        conn.setRequestProperty("Accept", "text/html")
                        conn.connect()
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val tokenRegex = Regex(""""token"\s*:\s*"([a-f0-9]{32})"""")
                        val token = tokenRegex.find(body)?.groupValues?.get(1)
                        Timber.d("StreetMain: login intercept HTML length=${body.length}, token=${token?.take(8)}")
                        if (token != null) {
                            MMKV.defaultMMKV().encode("web-api-csrf-token", token)
                        }
                        return android.webkit.WebResourceResponse("text/html", "utf-8", body.byteInputStream())
                    } catch (e: Exception) {
                        Timber.e(e, "StreetMain: login intercept failed")
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Timber.d("StreetMain: WebView onPageFinished url=$url")
                if (!cookieSaved) {
                    checkAndSaveCookie()
                } else if (url?.contains("www.pixiv.net") == true) {
                    cleanupWebView()
                    Toast.makeText(mContext, getString(R.string.street_web_login_success), Toast.LENGTH_SHORT).show()
                    viewModel.refresh()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("pixiv.net")) return false
                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        baseBind.refreshLayout.addView(webView)
        webView.loadUrl("https://accounts.pixiv.net/login")
    }

    private var cookieSaved = false

    private fun checkAndSaveCookie() {
        if (cookieSaved) return
        val cookie = CookieManager.getInstance().getCookie("https://www.pixiv.net") ?: return
        if (!cookie.contains("PHPSESSID")) return

        cookieSaved = true
        Timber.d("StreetMain: PHPSESSID found, saving cookie")
        MMKV.defaultMMKV().putString(SessionManager.COOKIE_KEY, cookie)
        CsrfTokenProvider.clear()

        // Cookie 拿到了，接下来用 WebView 加载 pixiv.net 首页来提取 CSRF token
        // （OkHttp 拿不到 token 因为 Cloudflare/SSR 限制，但 WebView 可以执行 JS）
        baseBind.toolbarTitle.text = getString(R.string.street_title)
        loginWebView?.loadUrl("https://www.pixiv.net/")
    }

    private fun cleanupWebView() {
        loginWebView?.let {
            baseBind.refreshLayout.removeView(it)
            it.destroy()
        }
        loginWebView = null
        baseBind.toolbarTitle.text = getString(R.string.street_title)
        baseBind.recyclerView.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        loginWebView?.destroy()
        loginWebView = null
        super.onDestroyView()
    }

    // ---- Adapter ---------------------------------------------------------------

    private inner class StreetAdapter : RecyclerView.Adapter<StreetViewHolder>() {

        private val data get() = viewModel.items.value ?: emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreetViewHolder {
            val binding = ItemStreetContentBinding.inflate(layoutInflater, parent, false)
            return StreetViewHolder(binding)
        }

        override fun onBindViewHolder(holder: StreetViewHolder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size
    }

    private inner class StreetViewHolder(
        private val binding: ItemStreetContentBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(content: StreetContent) {
            val thumb = content.thumbnails?.firstOrNull() ?: return
            val kind = content.kind ?: ""

            binding.titleText.text = thumb.title.orEmpty()
            binding.authorText.text = thumb.userName.orEmpty()

            binding.badgeType.text = kind
            val pageCount = thumb.pageCount ?: 0
            if (pageCount > 1) {
                binding.badgePage.visibility = View.VISIBLE
                binding.badgePage.text = "${pageCount}P"
            } else {
                binding.badgePage.visibility = View.GONE
            }

            val imageUrl = resolveImageUrl(thumb, kind)
            val iv = binding.thumbImage
            if (imageUrl != null) {
                iv.visibility = View.VISIBLE
                Glide.with(mContext)
                    .load(GlideUrlChild(imageUrl))
                    .into(iv)
            } else {
                iv.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(thumb, kind) }
        }
    }

    private fun resolveImageUrl(thumb: StreetThumbnail, kind: String): String? = when (kind) {
        "illust", "manga" -> thumb.pages?.firstOrNull()?.urls?.best
        "novel", "collection" -> thumb.url
        else -> null
    }

    private fun onItemClick(thumb: StreetThumbnail, kind: String) {
        val id = thumb.id?.toLongOrNull() ?: return
        when (kind) {
            "illust", "manga" -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val illust = withContext(Dispatchers.IO) {
                            Client.appApi.getIllust(id).illust
                        } ?: return@launch
                        val bean = Shaft.sGson.let { g ->
                            g.fromJson(g.toJson(illust), IllustsBean::class.java)
                        }
                        val uuid = UUID.randomUUID().toString()
                        Container.get().addPageToMap(PageData(uuid, null, listOf(bean)))
                        startActivity(Intent(mContext, VActivity::class.java).apply {
                            putExtra(Params.POSITION, 0)
                            putExtra(Params.PAGE_UUID, uuid)
                        })
                    } catch (_: Exception) {
                        Toast.makeText(mContext, "加载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "novel" -> {
                startActivity(Intent(mContext, TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                    putExtra(Params.NOVEL_ID, id)
                })
            }
        }
    }
}
