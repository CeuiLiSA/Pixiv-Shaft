package ceui.lisa.fragments

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.lisa.databinding.ItemStreetContentBinding
import ceui.lisa.databinding.ItemStreetRailBinding
import ceui.lisa.databinding.ItemStreetRailTagBinding
import ceui.lisa.databinding.ItemStreetRailWorkBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ClientManager
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.StreetContent
import ceui.loxia.StreetPage
import ceui.loxia.StreetPickup
import ceui.loxia.StreetThumbnail
import ceui.loxia.StreetTrendTag
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.ppppx
import ceui.pixiv.widgets.LoadMoreScrollListener
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.pixiv.widgets.scrollUpFrom
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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

private const val STREET_SPAN_COUNT = 2

/** 展示宽高比（高/宽）的钳制区间，对齐 IAdapter / IllustStaggerRenderer 的瀑布流口径。 */
private const val MIN_HEIGHT_RATIO = 0.6f
private const val MAX_HEIGHT_RATIO = 2.0f

/**
 * 小说封面比例。`/ajax/street/v2/main` 的 novel 缩略图只给一个裸 url、没有任何尺寸字段，
 * 但封面一律走 `c/600x600/novel-cover-master`，pixiv 统一压成 427x600（实测三张同尺寸）。
 */
private const val NOVEL_COVER_HEIGHT_RATIO = 600f / 427f

/**
 * 合集缩略图比例。collection 同样不给尺寸字段，但 url 里写死了 `/288x288/thumbnail`，是正方形。
 * 顺带说明：这个 embed.pixiv.net 地址实测恒回 400，图根本加载不出来 —— 正因如此更要提前定高，
 * 否则整张卡会塌成两行字。
 */
private const val COLLECTION_THUMB_HEIGHT_RATIO = 1f

/** 兜底比例：真遇到既没尺寸也没约定的类型，按正方形排，至少不会塌。 */
private const val FALLBACK_HEIGHT_RATIO = 1f

/** 列表条目类型。两种通栏货架 + 半栏单卡 + 尾部转圈。 */
private const val TYPE_WORK = 0
private const val TYPE_RAIL_WORKS = 1
private const val TYPE_RAIL_TAGS = 2
private const val TYPE_FOOTER = 3

/** 货架内部（横向条）的格子类型，两种格子共用一个 RecycledViewPool，必须各占一号。 */
private const val TYPE_RAIL_CELL_WORK = 10
private const val TYPE_RAIL_CELL_TAG = 11

/** 内容边距 6dp（列表）+ 6dp（卡片外边距）= V3 的 12dp。货架自己也按这个口径对齐。 */
private const val LIST_EDGE_DP = 6
private const val CARD_MARGIN_DP = 6

/** 货架格子边长，与 item_street_rail_work / _tag 里的 120dp 对应。 */
private const val RAIL_CELL_SIZE_DP = 120

/** pixiv 对「评论本体是表情/贴纸」的 pickup 回的占位串，没有可读文本。 */
private const val PICKUP_STAMP_PLACEHOLDER = "(normal)"

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
            StaggeredGridLayoutManager(STREET_SPAN_COUNT, StaggeredGridLayoutManager.VERTICAL)
        baseBind.recyclerView.adapter = adapter
        // 内容边距分两半：列表出这 6dp，卡片自己的 layout_margin 出另 6dp —— 加起来是 V3 的
        // 12dp，而卡与卡之间自然是 12dp 沟。通栏货架吃到的只有列表这 6dp，它内部再补 6dp，
        // 于是标题、首个格子和单卡左边缘落在同一条线上。
        baseBind.recyclerView.setPadding(LIST_EDGE_DP.ppppx, 0, LIST_EDGE_DP.ppppx, 0)
        baseBind.recyclerView.clipToPadding = false

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
                // 在跑但页面还空着 = 首屏,借下拉刷新那圈当加载指示(它本来只在手势时转,
                // 首次进来直接白屏,用户看不出到底是在加载还是拉空了);已有内容则是翻页,
                // 转圈落到列表尾部。
                is StreetMainViewModel.LoadState.Loading -> {
                    if (adapter.itemCount == 0) {
                        // 首屏这一发可能早于刷新层完成布局,那时置 isRefreshing 是不显示的;
                        // post 到布局后再置,并复查还在不在加载,免得请求已经回来了还留个空转的圈。
                        baseBind.refreshLayout.post {
                            if (viewModel.loadState.value == StreetMainViewModel.LoadState.Loading) {
                                baseBind.refreshLayout.isRefreshing = true
                            }
                        }
                    } else {
                        adapter.syncFooter(true)
                    }
                }
                is StreetMainViewModel.LoadState.Refreshed -> {
                    adapter.resetFooter(viewModel.hasMore)
                    adapter.notifyDataSetChanged()
                    baseBind.refreshLayout.isRefreshing = false
                }
                is StreetMainViewModel.LoadState.LoadedMore -> {
                    // 先按数据的增量报,再单独校尾部那一条 —— 两件事混在一次 notify 里
                    // 会差出一条,RecyclerView 直接抛 Inconsistency。
                    adapter.notifyItemRangeInserted(state.insertStart, state.insertCount)
                    adapter.syncFooter(viewModel.hasMore)
                }
                is StreetMainViewModel.LoadState.Error -> {
                    baseBind.refreshLayout.isRefreshing = false
                    adapter.syncFooter(false)
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
        MMKV.defaultMMKV().removeValueForKey(SessionManager.COOKIE_KEY)
        CsrfTokenProvider.clear()
        showWebLoginDialog()
    }

    override fun lazyData() {
        val cookies = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "")
        if (!SessionManager.isLoggedInWebCookie(cookies)) {
            // 含匿名 PHPSESSID 的旧存量也走这里重新登录,不然会卡在「拿不到 CSRF token」。
            if (autoStartWebLogin()) {
                startWebLogin()
            } else {
                showWebLoginDialog()
            }
        } else if (CsrfTokenProvider.get() == null) {
            // 有 cookie 但没 CSRF token，用 WebView 静默提取
            fetchCsrfViaWebView()
        } else {
            viewModel.refresh()
        }
    }

    /** 从按 tag 筛选的空态「去登录」进入时直接开始网页登录，不弹确认对话框。 */
    private fun autoStartWebLogin(): Boolean =
        activity?.intent?.getBooleanExtra(Params.AUTO_WEB_LOGIN, false) == true

    /**
     * Cookie 已有，但 CSRF token 缺失。用隐藏 WebView 加载 pixiv.net，
     * 让 WebView 自行处理 Cloudflare JS Challenge，然后通过 evaluateJavascript 提取 token。
     */
    private fun fetchCsrfViaWebView() {
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
        WitDialog.MessageDialogBuilder(mActivity)
            .setTitle(getString(R.string.street_web_login_title))
            .setMessage(getString(R.string.street_web_login_message))
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
                if (!cookieSaved) {
                    checkAndSaveCookie()
                } else if (url?.contains("www.pixiv.net") == true) {
                    // 登录完成后 WebView 加载了首页，用 JS 提取 CSRF token
                    val webView = view ?: return
                    extractCsrfToken(webView) { token ->
                        cleanupWebView()
                        if (token != null) {
                            Toaster.showShort(getString(R.string.street_web_login_success))
                            if (autoStartWebLogin()) {
                                // 从标签筛选空态进入：登录完成直接返回来源页，来源页在 onResume 里自动刷新
                                activity?.finish()
                            } else {
                                viewModel.refresh()
                            }
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

    /**
     * 三种条目：单卡（半栏）+ 作品货架 / 标签货架（两者通栏）。
     * 通栏靠 [StaggeredGridLayoutManager.LayoutParams.isFullSpan]，在 bind 时按类型现设。
     */
    private inner class StreetAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        /** 所有货架共用一个回收池：横向条的卡片长得一样，跨货架复用能省掉大量 inflate。 */
        private val railPool = RecyclerView.RecycledViewPool()

        private val data get() = viewModel.items.value ?: emptyList()

        /**
         * 尾部转圈是否在列表里。它参与 [getItemCount]，所以只能通过 [syncFooter] 改 ——
         * 改一步必须紧跟一次 notify，否则 RecyclerView 会拿「宣称的增量」和「真实条数」对不上。
         */
        var footerShown = false
            private set

        override fun getItemViewType(position: Int): Int {
            if (position >= data.size) return TYPE_FOOTER
            return when (data[position].kind) {
                KIND_CAROUSEL -> TYPE_RAIL_WORKS
                KIND_TAGS_CAROUSEL -> TYPE_RAIL_TAGS
                else -> TYPE_WORK
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_RAIL_WORKS, TYPE_RAIL_TAGS ->
                    RailViewHolder(ItemStreetRailBinding.inflate(layoutInflater, parent, false), railPool)
                TYPE_FOOTER -> object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.section_v3_loading_more, parent, false)
                ) {}
                else ->
                    StreetViewHolder(ItemStreetContentBinding.inflate(layoutInflater, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan =
                holder !is StreetViewHolder
            when (holder) {
                is RailViewHolder -> holder.bind(data[position])
                is StreetViewHolder -> holder.bind(data[position])
                else -> Unit
            }
        }

        override fun getItemCount(): Int = data.size + if (footerShown) 1 else 0

        /** 只在真的要变时动，且自己把那一条 notify 发全。 */
        fun syncFooter(show: Boolean) {
            if (footerShown == show) return
            footerShown = show
            if (show) notifyItemInserted(data.size) else notifyItemRemoved(data.size)
        }

        /** 配合 notifyDataSetChanged 用：不单独发 notify，由调用方一并刷新。 */
        fun resetFooter(show: Boolean) {
            footerShown = show
        }
    }

    private inner class StreetViewHolder(
        private val binding: ItemStreetContentBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(content: StreetContent) {
            val thumb = content.thumbnails?.firstOrNull() ?: return
            val kind = content.kind ?: ""

            binding.titleText.text = thumb.title.orEmpty()
            binding.authorText.text = thumb.userName.orEmpty()
            binding.badgeType.text = metaLine(thumb, kind)
            bindPickup(binding, content.pickup)

            binding.badgeR18.isVisible = (thumb.xRestrict ?: 0) > 0
            // aiType: 1 = 人工, 2 = AI 生成（网页端同款口径）
            binding.badgeAi.isVisible = thumb.aiType == 2
            binding.badgeOriginal.isVisible = thumb.isOriginal == true

            val pageCount = thumb.pageCount ?: 0
            if (pageCount > 1) {
                binding.badgePage.visibility = View.VISIBLE
                binding.badgePage.text = "${pageCount}P"
            } else {
                binding.badgePage.visibility = View.GONE
            }

            val display = resolveDisplay(thumb, kind)
            val iv = binding.thumbImage
            // 先定高再加载：格子多高只跟元数据有关，跟图片到没到、能不能到都无关。
            iv.setHeightRatio(display.heightRatio)
            if (display.url != null) {
                iv.visibility = View.VISIBLE
                // 请求尺寸显式 override，让请求宽高比恒等于展示宽高比 —— centerCrop 下
                // into(ImageView) 会按「请求尺寸」的比例在解码阶段裁图，交给 Glide 自己量
                // 就会读到复用卡片上一条目残留的旧宽高（setHeightRatio 只 requestLayout，
                // bind 又发生在 measure 之前），图会被裁成一小块还发糊。同 IllustStaggerRenderer。
                val columnWidth = columnWidthPx
                Glide.with(mContext)
                    .load(GlideUrlChild(display.url))
                    .override(columnWidth, (columnWidth * display.heightRatio).toInt())
                    // 占位底色 = 骨架块同款；collection 那种恒 400 的图也就停在这个色块上，
                    // 卡片不会塌、标题不会顶到 badge 上。
                    .placeholder(ceui.pixiv.feeds.R.color.feed_skeleton_block)
                    .into(iv)
            } else {
                iv.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(thumb, kind) }
        }
    }

    // ---- 通栏货架 ---------------------------------------------------------------

    private inner class RailViewHolder(
        private val binding: ItemStreetRailBinding,
        pool: RecyclerView.RecycledViewPool,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.railRv.layoutManager =
                LinearLayoutManager(mContext, RecyclerView.HORIZONTAL, false)
            binding.railRv.setRecycledViewPool(pool)
        }

        fun bind(content: StreetContent) {
            if (content.kind == KIND_TAGS_CAROUSEL) {
                binding.railLabel.text = getString(R.string.street_label_trending_tags)
                binding.railSeeAll.isVisible = false
                binding.railRv.adapter = TrendTagAdapter(
                    content.trendTags.orEmpty(),
                    content.thumbnails.orEmpty(),
                )
            } else {
                binding.railLabel.text = content.title.orEmpty()
                val seeAll = content.seeAllUrl
                binding.railSeeAll.isVisible = !seeAll.isNullOrBlank()
                binding.railSeeAll.setOnClickListener { openWebPath(content.title, seeAll) }
                binding.railRv.adapter = RailWorkAdapter(content.thumbnails.orEmpty())
            }
        }
    }

    private inner class RailWorkAdapter(
        private val items: List<StreetThumbnail>,
    ) : RecyclerView.Adapter<RailWorkViewHolder>() {

        // 与 [TrendTagAdapter] 共用一个 RecycledViewPool，view type 必须区分开，
        // 否则池子会把标签格递给作品 holder（两边默认都是 0）。
        override fun getItemViewType(position: Int) = TYPE_RAIL_CELL_WORK

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RailWorkViewHolder(ItemStreetRailWorkBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RailWorkViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private inner class RailWorkViewHolder(
        private val binding: ItemStreetRailWorkBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(thumb: StreetThumbnail) {
            binding.workTitle.text = thumb.title.orEmpty()
            binding.workAuthor.text = thumb.userName.orEmpty()
            binding.badgeR18.isVisible = (thumb.xRestrict ?: 0) > 0
            val pageCount = thumb.pageCount ?: 0
            binding.pagesBadge.isVisible = pageCount > 1
            if (pageCount > 1) binding.pagesBadge.text = "${pageCount}P"
            loadSquare(binding.workImage, thumb)
            // 货架里作品的类型挂在 thumbnail 自己身上（content.kind 恒为 "carousel"）。
            // 图那一层自己 clickable（为了 ripple），点击到不了 root，两层都得挂。
            val open = View.OnClickListener { onItemClick(thumb, thumb.type ?: "illust") }
            binding.workBox.setOnClickListener(open)
            binding.root.setOnClickListener(open)
        }
    }

    private inner class TrendTagAdapter(
        private val tags: List<StreetTrendTag>,
        private val covers: List<StreetThumbnail>,
    ) : RecyclerView.Adapter<TrendTagViewHolder>() {

        override fun getItemViewType(position: Int) = TYPE_RAIL_CELL_TAG

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TrendTagViewHolder(ItemStreetRailTagBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: TrendTagViewHolder, position: Int) {
            // trendTags 与 thumbnails 在响应里按下标一一对应；万一某天不对齐了，缺图也不能崩
            holder.bind(tags[position], covers.getOrNull(position))
        }

        override fun getItemCount() = tags.size
    }

    private inner class TrendTagViewHolder(
        private val binding: ItemStreetRailTagBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tag: StreetTrendTag, cover: StreetThumbnail?) {
            val name = tag.name.orEmpty()
            binding.tagName.text = "#$name"
            val translated = tag.translatedName?.takeIf { it.isNotBlank() && it != name }
            binding.tagTranslated.isVisible = translated != null
            binding.tagTranslated.text = translated.orEmpty()
            val count = tag.taggedCount ?: 0
            binding.tagCount.isVisible = count > 0
            if (count > 0) binding.tagCount.text = formatCount(count)
            loadSquare(binding.tagImage, cover)
            // 同 RailWorkViewHolder：图那一层 clickable，点击落不到 root，两层都要挂
            val open = View.OnClickListener {
                if (name.isEmpty()) return@OnClickListener
                startActivity(Intent(mContext, SearchActivity::class.java).apply {
                    putExtra(Params.KEY_WORD, name)
                    putExtra(Params.INDEX, 0)
                })
            }
            binding.tagBox.setOnClickListener(open)
            binding.root.setOnClickListener(open)
        }
    }

    /** 货架格子是正方形，正好吃 540x540 那档方裁缩略图，不必去拉等比的 master1200。 */
    private fun loadSquare(target: ImageView, thumb: StreetThumbnail?) {
        val url = thumb?.pages?.firstOrNull()?.urls?.let { it.medium ?: it.small ?: it.standard }
            ?: thumb?.url
        if (url == null) {
            Glide.with(mContext).clear(target)
            target.setImageDrawable(null)
            return
        }
        Glide.with(mContext)
            .load(GlideUrlChild(url))
            .override(RAIL_CELL_SIZE_DP.ppppx)
            .placeholder(R.color.v3_surface_2)
            .into(target)
    }

    /** 站点相对路径（如 `/bookmark_new_illust.php`）→ 内置浏览器。 */
    private fun openWebPath(title: String?, path: String?) {
        val url = path?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://www.pixiv.net$it"
        } ?: return
        startActivity(Intent(mContext, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            putExtra(Params.URL, url)
            putExtra(Params.TITLE, title ?: getString(R.string.street_title))
        })
    }

    // ---- 单卡细节 ---------------------------------------------------------------

    /**
     * 卡片底部那行 allCaps 小字。kind 之外把该类型独有的计数补上 —— 这些字段响应里一直有：
     * 小说给字数（[StreetThumbnail.useWordCount] 决定按「字」还是「文字」计）和收藏数，
     * 漫画给话数。
     */
    private fun metaLine(thumb: StreetThumbnail, kind: String): String {
        val parts = mutableListOf(kind)
        when (kind) {
            "novel" -> {
                val words = if (thumb.useWordCount == true) thumb.wordCount else thumb.textCount
                words?.takeIf { it > 0 }?.let {
                    parts += getString(R.string.street_meta_words, formatCount(it))
                }
                thumb.bookmarkCount?.takeIf { it > 0 }?.let {
                    parts += getString(R.string.street_meta_bookmarks, formatCount(it))
                }
            }
            "manga" -> thumb.episodeCount?.takeIf { it > 0 }?.let {
                parts += getString(R.string.street_meta_episodes, formatCount(it))
            }
        }
        return parts.joinToString(" · ")
    }

    /**
     * pickup：这条内容是被谁的哪句评论捞上首页的。响应里一直带着，之前整块没用。
     * 评论本体是表情/贴纸时 pixiv 回的是 `(normal)` 占位，那就只报人名。
     */
    private fun bindPickup(binding: ItemStreetContentBinding, pickup: StreetPickup?) {
        if (pickup?.userName.isNullOrBlank()) {
            binding.pickupRow.isVisible = false
            return
        }
        binding.pickupRow.isVisible = true
        val name = pickup!!.userName.orEmpty()
        val comment = pickup.comment?.trim()
            ?.takeIf { it.isNotEmpty() && it != PICKUP_STAMP_PLACEHOLDER }
        binding.pickupText.text = if (comment != null) {
            getString(R.string.street_pickup_comment, name, comment)
        } else {
            getString(R.string.street_pickup_plain, name)
        }
        val avatar = pickup.profileImageUrl
        if (avatar.isNullOrBlank()) {
            binding.pickupAvatar.setImageResource(R.drawable.v3_widget_avatar_placeholder)
        } else {
            Glide.with(mContext)
                .load(GlideUrlChild(avatar))
                .placeholder(R.drawable.v3_widget_avatar_placeholder)
                .into(binding.pickupAvatar)
        }
    }

    private fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

    /**
     * 单卡里图片的真实宽度（px）：列宽扣掉列表自身的横向 padding 和卡片外边距。
     * 取 LayoutManager 实时宽度（measure 先于绑定，旋转后已是新方向的值），首帧兜底屏宽。
     */
    private val columnWidthPx: Int
        get() {
            val listWidth = baseBind.recyclerView.layoutManager?.width?.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val inner = listWidth - LIST_EDGE_DP.ppppx * 2
            return (inner / STREET_SPAN_COUNT - CARD_MARGIN_DP.ppppx * 2).coerceAtLeast(1)
        }

    private data class ThumbDisplay(val url: String?, val heightRatio: Float)

    /**
     * 一条内容的图 + 展示宽高比。四类 kind 的尺寸都能在 bind 时确定，不必等 Glide 解码：
     *
     * - illust / manga：`pages[0].width/height` 就是原图尺寸，而取用的 `1200x1200_standard`
     *   （master1200）是等比缩，比例一致，直接可用。
     * - novel：响应没有尺寸字段，封面按 pixiv 统一规格算（[NOVEL_COVER_HEIGHT_RATIO]）。
     * - collection：响应没有尺寸字段，但 url 里写死 288x288，正方形。
     */
    private fun resolveDisplay(thumb: StreetThumbnail, kind: String): ThumbDisplay = when (kind) {
        "illust", "manga" -> {
            val page = thumb.pages?.firstOrNull()
            // pages 缺失（旧字段名/新类型）时退回裸 url，比例交给兜底值，别把整条丢掉。
            ThumbDisplay(page?.urls?.best ?: thumb.url, page.heightRatio())
        }
        "novel" -> ThumbDisplay(thumb.url, NOVEL_COVER_HEIGHT_RATIO)
        "collection" -> ThumbDisplay(thumb.url, COLLECTION_THUMB_HEIGHT_RATIO)
        else -> ThumbDisplay(null, FALLBACK_HEIGHT_RATIO)
    }

    /**
     * 原图宽高 → 展示宽高比。钳到 [MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO]：pixiv 上 1:2 以上的
     * 长条并不少见（实测有 4085x8850），不钳的话一张卡就能吃掉两屏。钳到的那部分由
     * centerCrop 裁掉，区间内的比例则严丝合缝，centerCrop 等价于 fitCenter，不会有裁切。
     */
    private fun StreetPage?.heightRatio(): Float {
        val w = this?.width ?: 0
        val h = this?.height ?: 0
        return if (w > 0 && h > 0) {
            (h.toFloat() / w).coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
        } else {
            FALLBACK_HEIGHT_RATIO
        }
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
                        val uuid = UUID.randomUUID().toString()
                        Container.get().addPageToMap(PageData(uuid, null, listOf(illust)))
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
