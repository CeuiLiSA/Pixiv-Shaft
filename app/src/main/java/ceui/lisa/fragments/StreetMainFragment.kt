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
import ceui.loxia.ClientManager
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.StreetContent
import ceui.loxia.StreetThumbnail
import ceui.pixiv.session.SessionManager
import ceui.pixiv.widgets.LoadMoreScrollListener
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.pixiv.widgets.scrollUpFrom
import com.bumptech.glide.Glide
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

private const val EXTRACT_TOKEN_JS = """
(function() {
    var meta = document.getElementById('meta-global-data');
    if (meta) {
        var c = meta.getAttribute('content');
        var m = c && c.match(/"token"\s*:\s*"([a-f0-9]{32})"/);
        if (m) return m[1];
    }
    var nd = document.getElementById('__NEXT_DATA__');
    if (nd) {
        var m2 = nd.textContent.match(/"token"\s*:\s*"([a-f0-9]{32})"/);
        if (m2) return m2[1];
    }
    var m3 = document.documentElement.innerHTML.match(/"token"\s*:\s*"([a-f0-9]{32})"/);
    return m3 ? m3[1] : null;
})()
"""

class StreetMainFragment : BaseLazyFragment<FragmentBaseListBinding>() {

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

        baseBind.refreshLayout.applyV3RefreshTheme()
        // 列表隔着 listContainer 挂在刷新层下,顶部判定得自己接到 RecyclerView 上,
        // 否则滚到中段往下拖也会被当成「已在顶部」触发刷新。
        baseBind.refreshLayout.scrollUpFrom(baseBind.recyclerView)
        baseBind.refreshLayout.setOnRefreshListener { viewModel.refresh() }
        // 翻页改由滚动触发(SwipeRefreshLayout 没有上拉 footer)。到底了就别再喂请求;
        // 重入由 StreetMainViewModel.load 的 Loading 守卫兜住。
        baseBind.recyclerView.addOnScrollListener(
            LoadMoreScrollListener({ if (viewModel.hasMore) viewModel.loadMore() })
        )

        viewModel.loadState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is StreetMainViewModel.LoadState.Refreshed -> {
                    adapter.notifyDataSetChanged()
                    baseBind.refreshLayout.isRefreshing = false
                }
                is StreetMainViewModel.LoadState.LoadedMore -> {
                    adapter.notifyItemRangeInserted(state.insertStart, state.insertCount)
                }
                is StreetMainViewModel.LoadState.Error -> {
                    baseBind.refreshLayout.isRefreshing = false
                    Toast.makeText(mContext, state.message, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

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
     * 让 WebView 自行处理 Cloudflare JS Challenge，然后通过 evaluateJavascript 提取 token。
     */
    private fun fetchCsrfViaWebView() {
        Timber.d("StreetMain: have cookie but no CSRF, fetching via WebView")
        baseBind.toolbarTitle.text = getString(R.string.street_title)
        // CSRF 没就绪前下拉刷新必失败(refresh() 直接抛"token 未就绪"的 toast),先关掉手势,
        // cleanupWebView 里恢复。legacy 的 FalsifyHeader 本来就不触发刷新,这是对齐旧行为。
        baseBind.refreshLayout.isEnabled = false

        val webView = WebView(mContext).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = ClientManager.WEB_USER_AGENT
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("www.pixiv.net") != true) return
                view?.evaluateJavascript(EXTRACT_TOKEN_JS) { result ->
                    val token = result?.trim('"')?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    Timber.d("StreetMain: evaluateJavascript token=${token?.take(8)}")
                    if (token != null) {
                        MMKV.defaultMMKV().encode("web-api-csrf-token", token)
                    }
                    // 顺便更新 cookie（WebView 可能刷新了 cf_clearance 等）
                    CookieManager.getInstance().getCookie("https://www.pixiv.net")?.let { freshCookie ->
                        if (freshCookie.contains("PHPSESSID")) {
                            MMKV.defaultMMKV().putString(SessionManager.COOKIE_KEY, freshCookie)
                        }
                    }
                    cleanupWebView()
                    if (CsrfTokenProvider.get() != null) {
                        viewModel.refresh()
                    } else {
                        Toast.makeText(mContext, "无法获取 CSRF token，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        baseBind.listContainer.addView(webView)
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
        // 登录 WebView 盖满 listContainer 期间必须关掉下拉刷新:此时 scrollUpFrom 的唯一候选
        // recyclerView 是 GONE,canChildScrollUp 恒 false → 在登录页里往回滚(手指下滑)会被
        // SwipeRefreshLayout 拦截成刷新手势——既抢走 WebView 的滚动,又在登录中途乱发 refresh()。
        baseBind.refreshLayout.isEnabled = false

        val ua = ClientManager.WEB_USER_AGENT
        val webView = WebView(mContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = ua
        }
        loginWebView = webView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Timber.d("StreetMain: WebView onPageFinished url=$url")
                if (!cookieSaved) {
                    checkAndSaveCookie()
                } else if (url?.contains("www.pixiv.net") == true) {
                    // 登录完成后 WebView 加载了首页，用 JS 提取 CSRF token
                    view?.evaluateJavascript(EXTRACT_TOKEN_JS) { result ->
                        val token = result?.trim('"')?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                        Timber.d("StreetMain: login evaluateJavascript token=${token?.take(8)}")
                        if (token != null) {
                            MMKV.defaultMMKV().encode("web-api-csrf-token", token)
                        }
                        cleanupWebView()
                        Toast.makeText(mContext, getString(R.string.street_web_login_success), Toast.LENGTH_SHORT).show()
                        viewModel.refresh()
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("pixiv.net")) return false
                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        baseBind.listContainer.addView(webView)
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
            baseBind.listContainer.removeView(it)
            it.destroy()
        }
        loginWebView = null
        baseBind.toolbarTitle.text = getString(R.string.street_title)
        baseBind.recyclerView.visibility = View.VISIBLE
        baseBind.refreshLayout.isEnabled = true
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
