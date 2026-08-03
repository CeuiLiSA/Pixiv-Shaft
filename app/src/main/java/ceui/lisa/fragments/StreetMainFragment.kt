package ceui.lisa.fragments

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.hjq.toast.Toaster
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * 从页面里抠 CSRF token。
 *
 * 引号必须写成可选转义（`token\"` / `token"`）：pixiv 现在把 token 埋在 `__NEXT_DATA__` 的
 * **嵌套 JSON 字符串**里，textContent 拿到的原文是 `\"token\":\"<32hex>\"`——按裸引号
 * (`"token":"…"`) 匹配一个都对不上，三条策略会一起落空、回 null。
 * `window.pixiv.context` / `globalInitData` 是 Next.js 改版前的老全局量，现已不存在；
 * `meta-global-data` 同理。留着不碍事，真正命中的是 `__NEXT_DATA__` 那条。
 */
private const val EXTRACT_TOKEN_JS = """
(function() {
    var RE = /token\\?"\s*:\s*\\?"([a-f0-9]{32})/;
    var meta = document.getElementById('meta-global-data');
    if (meta) {
        var c = meta.getAttribute('content');
        var m = c && c.match(RE);
        if (m) return m[1];
    }
    var nd = document.getElementById('__NEXT_DATA__');
    if (nd) {
        var m2 = nd.textContent.match(RE);
        if (m2) return m2[1];
    }
    var m3 = document.documentElement.innerHTML.match(RE);
    return m3 ? m3[1] : null;
})()
"""

private val CSRF_TOKEN_FORMAT = Regex("[a-f0-9]{32}")

/** 首屏偶尔要等一拍才把 __NEXT_DATA__ 挂上，Cloudflare 挑战页也会白占一次 onPageFinished。 */
private const val CSRF_MAX_ATTEMPTS = 3
private const val CSRF_RETRY_DELAY_MS = 1000L

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
                    Toaster.showShort(state.message)
                }
                else -> Unit
            }
        }
    }

    private var loginWebView: WebView? = null

    /**
     * 在 [view] 上取 CSRF token；取不到就隔 [CSRF_RETRY_DELAY_MS] 再试，最多 [CSRF_MAX_ATTEMPTS] 次，
     * 仍失败则退到 [CsrfTokenProvider.fetch]（OkHttp 直抓首页，跟 WebView 是两条独立链路）。
     * 全部落空才回调 null。
     *
     * 单次 evaluateJavascript 即判死过于脆弱：抓不到时既没有重试，调用方又照样弹「登录成功」，
     * 紧接着列表刷新再弹一条「CSRF token 未就绪」——用户看到的就是"登录成功了还报错"。
     */
    private fun extractCsrfToken(view: WebView, attempt: Int = 1, onResult: (String?) -> Unit) {
        view.evaluateJavascript(EXTRACT_TOKEN_JS) { result ->
            val token = result?.trim('"')?.takeIf { CSRF_TOKEN_FORMAT.matches(it) }
            Timber.d("StreetMain: csrf attempt $attempt token=${token?.take(8)}")
            when {
                token != null -> {
                    CsrfTokenProvider.set(token)
                    onResult(token)
                }
                attempt < CSRF_MAX_ATTEMPTS -> view.postDelayed({
                    // WebView 可能已被 cleanupWebView 销毁，销毁后再 evaluate 会崩。
                    if (loginWebView === view) extractCsrfToken(view, attempt + 1, onResult)
                }, CSRF_RETRY_DELAY_MS)
                // 绑 viewLifecycleOwner:页面被销毁时协程一起取消,onResult 不会在
                // baseBind 已失效之后再回来碰 UI。
                else -> viewLifecycleOwner.lifecycleScope.launch {
                    val fallback = withContext(Dispatchers.IO) { CsrfTokenProvider.fetch() }
                    Timber.d("StreetMain: csrf okhttp fallback token=${fallback?.take(8)}")
                    onResult(fallback)
                }
            }
        }
    }

    /**
     * cookie 有了但 CSRF token 拿不到。这一页没 token 就发不出请求，放任下拉刷新只会把
     * 「CSRF token 未就绪」反复弹一遍。给一条明确提示 + 重新登录的入口，别让用户空转。
     */
    private fun onCsrfUnavailable() {
        baseBind.refreshLayout.isEnabled = false
        Toaster.showShort(getString(R.string.street_csrf_failed))
        showWebLoginDialog()
    }

    /**
     * 网页会话已失效（pixiv 把首页重定向回了登录页）。留着那份 cookie 只会让下次进来
     * 继续走"静默取 token"然后继续失败，直接清掉、重新登录。
     */
    private fun onWebSessionExpired() {
        Timber.d("StreetMain: web session expired, clearing cookie")
        MMKV.defaultMMKV().removeValueForKey(SessionManager.COOKIE_KEY)
        CsrfTokenProvider.clear()
        showWebLoginDialog()
    }

    override fun lazyData() {
        val cookies = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "")
        if (!SessionManager.isLoggedInWebCookie(cookies)) {
            // 含匿名 PHPSESSID 的旧存量也走这里重新登录,不然会卡在「拿不到 CSRF token」。
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
                // 会话失效时 pixiv 把首页重定向回登录页 —— 再等 token 是等不到的。原来这里
                // 直接 return，WebView 就一直挂着不清理，用户对着一个永远空白的列表没有提示。
                if (url?.contains("accounts.pixiv.net") == true) {
                    cleanupWebView()
                    onWebSessionExpired()
                    return
                }
                if (url?.contains("www.pixiv.net") != true) return
                val webView = view ?: return
                extractCsrfToken(webView) { token ->
                    // 顺便更新 cookie（WebView 可能刷新了 cf_clearance 等）
                    CookieManager.getInstance().getCookie("https://www.pixiv.net")?.let { freshCookie ->
                        // 只在仍是登录态时回写:会话过期后 WebView 拿到的是匿名 PHPSESSID,
                        // 覆盖上去等于把已登录的那份悄悄降级成匿名。
                        if (SessionManager.isLoggedInWebCookie(freshCookie)) {
                            MMKV.defaultMMKV().putString(
                                SessionManager.COOKIE_KEY,
                                SessionManager.normalizeWebCookie(freshCookie),
                            )
                        }
                    }
                    cleanupWebView()
                    if (token != null) viewModel.refresh() else onCsrfUnavailable()
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
        // 复位:登录可能被重来一次(取不到 token 后又走了一遍引导)。不清掉的话第二轮的
        // onPageFinished 会直接跳过 checkAndSaveCookie,新 cookie 永远存不下来。
        cookieSaved = false
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
                    val webView = view ?: return
                    extractCsrfToken(webView) { token ->
                        cleanupWebView()
                        if (token != null) {
                            Toaster.showShort(getString(R.string.street_web_login_success))
                            viewModel.refresh()
                        } else {
                            // 这里曾经无条件报「登录成功」，紧接着 refresh() 再弹一条
                            // 「CSRF token 未就绪」——两条自相矛盾的 toast 一起糊在用户脸上，
                            // 而 WebView 已销毁，除了退出重进没有任何重试手段。
                            onCsrfUnavailable()
                        }
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
        // 必须是「已登录」的 PHPSESSID(<uid>_<hash>)。这里曾经只判 contains("PHPSESSID"),
        // 而登录页一加载 pixiv 就先发匿名 PHPSESSID —— 于是 onPageFinished 第一帧就判成功、
        // 跳走首页、cleanupWebView 把登录框拆掉,用户压根没机会输账号密码;存下的匿名 cookie
        // 还让 hasWebCookie 恒真,连「去登录」的引导都被关掉。见 SessionManager.isLoggedInWebCookie。
        if (!SessionManager.isLoggedInWebCookie(cookie)) return

        cookieSaved = true
        Timber.d("StreetMain: PHPSESSID found, saving cookie")
        MMKV.defaultMMKV().putString(SessionManager.COOKIE_KEY, SessionManager.normalizeWebCookie(cookie))
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
                        Toaster.showShort("加载失败")
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
