package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ActivityLoginBinding
import ceui.lisa.databinding.ItemLanguageRowBinding
import ceui.pixiv.api.model.AccountResponse
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.pixiv.utils.Event
import ceui.pixiv.ui.settings.MoonSync
import ceui.pixiv.utils.observeEvent
import ceui.pixiv.i18n.AppLocales
import ceui.pixiv.login.PixivLogin
import ceui.pixiv.login.PixivOAuthResult
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder
import ceui.pixiv.witstudio.dialog.WitDialog.MessageDialogBuilder
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

/** refresh_token 登录的终局，由 [LandingViewModel] 发出、[FragmentLogin] 消费。 */
sealed class RefreshTokenLoginOutcome {
    /** 已换到 token 并落库完成，[uid] 供云端设置同步用。 */
    data class Succeeded(val uid: Long) : RefreshTokenLoginOutcome()
    data class Failed(@StringRes val messageRes: Int) : RefreshTokenLoginOutcome()
}

class LandingViewModel : ViewModel() {
    val isChecked = MutableLiveData(false)

    private val _refreshTokenLoginEvent = MutableLiveData<Event<RefreshTokenLoginOutcome>>()
    val refreshTokenLoginEvent: LiveData<Event<RefreshTokenLoginOutcome>> = _refreshTokenLoginEvent

    private var refreshTokenLoginJob: Job? = null

    /**
     * 拿用户粘贴的 refresh_token 换登录态。
     *
     * 走 [PixivLogin]（OAuth 专用客户端）而不是 [ceui.lisa.http.Retro] 的 AccountTokenApi：
     * 后者挂着 [ceui.lisa.http.TokenInterceptor]，token 无效时 pixiv 回的 400
     * "Invalid refresh token" 会被它当成「当前会话过期」，直接 logout + 重启 App —— 在登录页
     * 粘错一次 token 整个应用就被踢回主界面重来。OAuth 客户端本身不挂任何鉴权拦截器，
     * 失败按 [PixivOAuthResult.Failure] 的子类型分流即可。
     *
     * 放 [viewModelScope] 而不是 Fragment 的 viewLifecycleOwner.lifecycleScope：登录页宿主
     * TemplateActivity 没声明 configChanges，旋转 / 展开折叠屏 / 进分屏都会重建 View。挂在
     * view 上的话，token 已经换到手甚至已经落库，协程却被取消 —— 用户没有任何提示地停在
     * 登录页上，而 session 其实已经写进去了。结果用 [Event] 投递，重建后的 observer 照收。
     */
    fun startRefreshTokenLogin(refreshToken: String) {
        if (refreshTokenLoginJob?.isActive == true) return
        refreshTokenLoginJob = viewModelScope.launch {
            val outcome = when (val result = PixivLogin.refreshTokenForLogin(refreshToken)) {
                is PixivOAuthResult.Failure -> {
                    Timber.w("Credential login failed")
                    RefreshTokenLoginOutcome.Failed(refreshTokenFailureToast(result))
                }

                is PixivOAuthResult.Success -> persistRefreshTokenLogin(result.rawBody)
            }
            _refreshTokenLoginEvent.value = Event(outcome)
        }
    }

    private suspend fun persistRefreshTokenLogin(rawBody: String): RefreshTokenLoginOutcome {
        // rawBody 而不是 result.response：落库要的是完整 user（R18 设置、邮箱验证态等），
        // PixivOAuthResponse 只带最小档案。与 OutWakeActivity 的 OAuth 回调路径同款处理。
        val userModel = runCatching {
            Shaft.sGson.fromJson(rawBody, AccountResponse::class.java)
        }.getOrNull()
        val user = userModel?.user
            ?: return RefreshTokenLoginOutcome.Failed(R.string.refresh_token_invalid_toast)
        try {
            withContext(Dispatchers.IO) { Local.persistLoggedInUser(userModel) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Credential login persistence failed")
            // Session 已写（saveUser 成功）则用户已登录，Room 行缺失仅影响账户切换列表：
            // 视为非致命，继续流程，避免半登录态卡在登录页
            if (!SessionManager.isLoggedIn) {
                return RefreshTokenLoginOutcome.Failed(R.string.refresh_token_persist_error_toast)
            }
        }
        return RefreshTokenLoginOutcome.Succeeded(user.id)
    }
}

@StringRes
private fun refreshTokenFailureToast(failure: PixivOAuthResult.Failure): Int = when (failure) {
    // 断网 / 超时 / DNS → 网络文案，不要归因到 token
    is PixivOAuthResult.Failure.NetworkError -> R.string.refresh_token_network_error_toast
    is PixivOAuthResult.Failure.ServerRejected ->
        if (failure.httpCode in 500..599) R.string.refresh_token_server_error_toast
        // 400 invalid_grant / "Invalid refresh token" 都在这一支
        else R.string.refresh_token_invalid_toast
    // MissingCode / MissingVerifier 属于授权码流程，refresh 走不到，兜底当 token 无效
    else -> R.string.refresh_token_invalid_toast
}

class FragmentLogin : BaseFragment<ActivityLoginBinding>() {

    private val viewModel: LandingViewModel by viewModels()

    private val greetings = listOf(
        Greeting("en", "Welcome", "Choose your language"),
        Greeting("zh-CN", "欢迎", "选择你的语言"),
        Greeting("zh-TW", "歡迎", "選擇你的語言"),
        Greeting("ja", "ようこそ", "言語を選んでください"),
        Greeting("ko", "환영합니다", "언어를 선택하세요"),
        Greeting("ru", "Добро пожаловать", "Выберите язык"),
        Greeting("tr", "Hoş geldiniz", "Dilinizi seçin"),
    )

    private var selectedTag = "en"
    private var cycleIndex = 0
    private var greetingCycleJob: Job? = null
    private val rowChecks = mutableMapOf<String, View>()
    private var refreshTokenDialog: WitDialog? = null

    // ── Lifecycle ──

    override fun initLayout() {
        mLayoutID = R.layout.activity_login
    }

    override fun initView() {
        setupInsets()
        setupToolbar()

        // On API 33+, wait for shader to compile before showing content
        val waitForShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        if (waitForShader) {
            baseBind.tunnelBackground.alpha = 0f
            baseBind.gradientScrim.alpha = 0f
            baseBind.toolbar.alpha = 0f
            baseBind.tunnelBackground.onReadyListener = {
                val dur = 800L
                baseBind.tunnelBackground.animate().alpha(1f).setDuration(1200).start()
                baseBind.gradientScrim.animate().alpha(1f).setDuration(dur).start()
                baseBind.toolbar.animate().alpha(1f).setDuration(dur).start()
                baseBind.loadingSpinner.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { baseBind.loadingSpinner.visibility = View.GONE }
                    .start()
                val activePage = if (baseBind.languagePage.root.visibility != View.GONE)
                    baseBind.languagePage.root else baseBind.loginPage.root
                activePage.animate().alpha(1f).setDuration(dur).start()
            }
        } else {
            baseBind.loadingSpinner.visibility = View.GONE
        }

        if (AppLocales.hasUserConfigured) {
            baseBind.languagePage.root.visibility = View.GONE
            baseBind.loginPage.root.apply {
                visibility = View.VISIBLE
                alpha = 0f
                if (!waitForShader) {
                    animate().alpha(1f).setDuration(500).start()
                }
            }
        } else {
            if (waitForShader) {
                baseBind.languagePage.root.alpha = 0f
            }
            setupLanguagePage()
        }
        setupLoginPage()
    }

    override fun initData() {}

    override fun onDestroyView() {
        refreshTokenDialog?.dismiss()
        refreshTokenDialog = null
        super.onDestroyView()
    }

    // ── Insets ──

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            baseBind.toolbar.setPadding(0, bars.top, 0, 0)
            baseBind.languagePage.greetingHero.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = dp(20f) + bars.top
            }
            baseBind.languagePage.continueButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(20f) + bars.bottom
            }
            baseBind.loginPage.bottomLinear.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(20f) + bars.bottom
            }
            baseBind.loginPage.bottomLinear.apply {
                setPadding(paddingLeft, paddingTop, paddingRight, bars.bottom)
            }
            insets
        }
    }

    // ── Toolbar ──

    private fun setupToolbar() {
        // 登录页左上角返回选语言页（图标只在进入登录页后显示）
        baseBind.toolbar.setNavigationOnClickListener { backToLanguagePage() }
        inflateToolbarMenu()
        baseBind.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(mContext, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SETTINGS.key)
                    })
                    true
                }

                R.id.action_import -> {
                    val json = ClipBoardUtils.getClipboardContent(mContext)
                    if (!json.isNullOrEmpty() && json.contains(Params.USER_KEY)) {
                        performLogin(json)
                    } else {
                        Common.showToast("剪贴板无用户信息", 3)
                    }
                    true
                }

                R.id.action_refresh_token_login -> {
                    showRefreshTokenDialog()
                    true
                }

                R.id.action_browser_login -> {
                    showBrowserPicker(PixivLogin.startLoginUrl())
                    true
                }

                R.id.action_browser_signup -> {
                    showBrowserPicker(PixivLogin.startSignUrl())
                    true
                }

                else -> false
            }
        }
    }

    /**
     * inflate overflow 菜单并按渠道剔项。两个 inflate 点（初始化 / 切语言后重建）都必须走这里，
     * 否则 lite 渠道切一次语言就会把隐藏掉的项重新放出来。
     */
    private fun inflateToolbarMenu() {
        baseBind.toolbar.inflateMenu(R.menu.login_menu)
        // 与「从邮箱恢复」同样在 lite 渠道隐藏,理由见 setupLoginPage 里那段注释。
        baseBind.toolbar.menu.findItem(R.id.action_refresh_token_login)?.isVisible =
            !BuildConfig.IS_LITE
    }

    // ── Browser picker ──

    private data class BrowserItem(
        val label: String,
        val packageName: String,
        val activityName: String,
    )

    /**
     * 自建「打开方式」弹窗：列出已安装的浏览器，选中后用显式 Intent 打开 [url]。
     * 不直接 ACTION_VIEW 交给系统，是因为国内 ROM 上电商 / 下载器类 App 会抢 https
     * 打开权，登录 URL 被劫走就完成不了 OAuth。
     */
    private fun showBrowserPicker(url: String) {
        // PackageManager 查询和 loadLabel 都是跨进程调用，放 IO 线程；pm 必须在主线程
        // 先取好再进协程 —— IO 块里调 requireContext() 会在 fragment 恰好销毁时抛
        // IllegalStateException 直接崩掉。
        val pm = requireContext().packageManager
        viewLifecycleOwner.lifecycleScope.launch {
            val browsers = withContext(Dispatchers.IO) { queryBrowsers(pm) }
            showBrowserDialog(url, browsers)
        }
    }

    /** 枚举已安装的真浏览器，常用浏览器排前，其余按名称排。 */
    private fun queryBrowsers(pm: PackageManager): List<BrowserItem> {
        // 框架枚举浏览器的标准姿势：scheme-only 的 "https:"（无 host）。真浏览器的
        // intent-filter 只声明 scheme、能接任意网址，所以匹配；只注册了特定 host
        // deep link 的 App（官方 pixiv 客户端、本应用自己之类）因为 filter 带
        // authority、要求 URI 提供 host，天然不匹配，不用逐个点名。
        val browserProbe = Intent(Intent.ACTION_VIEW, Uri.fromParts("https", "", null)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return pm.queryIntentActivities(browserProbe, PackageManager.MATCH_ALL)
            // 同样按 scheme 通配注册的劫持类 App（电商 / 下载器）筛不出来，黑名单兜底。
            .filterNot { it.activityInfo.name in HIJACKER_ACTIVITY_NAMES }
            .map { resolveInfo ->
                BrowserItem(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = resolveInfo.activityInfo.packageName,
                    activityName = resolveInfo.activityInfo.name,
                )
            }
            .sortedWith(compareBy<BrowserItem> { item ->
                val isPriority = PRIORITY_BROWSER_KEYWORDS.any { keyword ->
                    item.packageName.contains(keyword, ignoreCase = true) ||
                            item.label.contains(keyword, ignoreCase = true)
                }
                if (isPriority) 0 else 1
            }.thenBy { it.label })
    }

    private fun showBrowserDialog(url: String, browsers: List<BrowserItem>) {
        if (browsers.isEmpty()) {
            Common.showToast(getString(R.string.msg_no_browser))
            return
        }

        MenuDialogBuilder(mContext)
            .setTitle(getString(R.string.browser_dialog_found_title))
            .addItems(browsers.map { it.label }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                val target = browsers[which]
                val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    component = ComponentName(target.packageName, target.activityName)
                }
                try {
                    startActivity(launchIntent)
                } catch (_: ActivityNotFoundException) {
                    // 弹窗挂着的这段时间里目标浏览器可能刚好被卸载
                    Common.showToast(getString(R.string.msg_no_browser))
                }
            }
            .show()
    }

    // ── Language page ──

    private fun setupLanguagePage() {
        selectedTag = matchSystemOrFallback()
        cycleIndex = greetings.indexOfFirst { it.tag == selectedTag }.coerceAtLeast(0)
        applyGreeting(greetings[cycleIndex])

        buildRows(baseBind.languagePage.rowsContainer)
        applyContinueLabel()
        startGreetingCycle()

        baseBind.languagePage.continueButton.setOnClickListener { transitionToLogin() }
    }

    private fun buildRows(container: LinearLayout) {
        container.removeAllViews()
        rowChecks.clear()

        AppLocales.supportedTags.forEachIndexed { idx, tag ->
            val row = ItemLanguageRowBinding.inflate(layoutInflater, container, false)
            row.languageLabel.text = AppLocales.displayName(tag)
            row.languageCheck.alpha = if (tag == selectedTag) 1f else 0f
            row.root.setOnClickListener { onRowSelected(tag) }

            container.addView(row.root)
            rowChecks[tag] = row.languageCheck

            if (idx < AppLocales.supportedTags.lastIndex) {
                View(container.context).apply { setBackgroundColor(0x33FFFFFF) }.also {
                    container.addView(
                        it, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f).coerceAtLeast(1)
                        )
                    )
                }
            }
        }
    }

    private fun onRowSelected(tag: String) {
        if (tag == selectedTag) return
        rowChecks[selectedTag]?.animate()?.alpha(0f)?.setDuration(160)?.start()
        rowChecks[tag]?.animate()?.alpha(1f)?.setDuration(160)?.start()
        selectedTag = tag
        cycleIndex = greetings.indexOfFirst { it.tag == tag }.coerceAtLeast(0)
        fadeGreetingTo(greetings[cycleIndex])
        applyContinueLabel()
    }

    private fun startGreetingCycle() {
        greetingCycleJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(2200L)
                    cycleIndex = (cycleIndex + 1) % greetings.size
                    fadeGreetingTo(greetings[cycleIndex])
                }
            }
        }
    }

    private fun applyGreeting(g: Greeting) {
        baseBind.languagePage.greetingHero.text = g.hero
        baseBind.languagePage.greetingSubtitle.text = g.subtitle
    }

    private fun fadeGreetingTo(g: Greeting) {
        val hero = baseBind.languagePage.greetingHero
        val subtitle = baseBind.languagePage.greetingSubtitle
        subtitle.animate().alpha(0f).setDuration(180).start()
        hero.animate().alpha(0f).setDuration(180).withEndAction {
            applyGreeting(g)
            hero.animate().alpha(1f).setDuration(260).start()
            subtitle.animate().alpha(0.75f).setDuration(260).start()
        }.start()
    }

    private fun applyContinueLabel() {
        baseBind.languagePage.continueButton.text = when (selectedTag) {
            "zh-CN" -> "继续"
            "zh-TW" -> "繼續"
            "ja" -> "続ける"
            "ko" -> "계속"
            "ru" -> "Продолжить"
            "tr" -> "Devam"
            else -> "Continue"
        }
    }

    private fun matchSystemOrFallback(): String {
        val sys = Locale.getDefault()
        val exact = AppLocales.supportedTags.firstOrNull {
            val l = Locale.forLanguageTag(it)
            l.language == sys.language && l.country.equals(sys.country, ignoreCase = true)
        }
        if (exact != null) return exact
        return AppLocales.supportedTags.firstOrNull {
            Locale.forLanguageTag(it).language == sys.language
        } ?: "en"
    }

    // ── Page transition ──

    /**
     * 选完语言后，原地切到登录页 —— 关键是不触发 AppCompat 的 recreate，否则 shader 背景、greeting
     * 切换动画、loading spinner 全部从零再来一遍，体感非常糟。
     *
     * 步骤：
     *  1. 只把 tag 写进 MMKV ([AppLocales.saveTag])，**不**调 `setApplicationLocales`；
     *  2. 把当前 Activity 的 Resources 配置就地切到目标 locale，让后续 `getString(...)`、dialog、
     *     toast 自动用上新文案 ([AppLocales.applyConfigurationInPlace])；
     *  3. 重新塞登录页里已经 inflate 出来的几个 TextView 的文本（XML `android:text="@string/..."`
     *     的值在 inflate 那一刻就固定了，光改 Configuration 不会刷新它们）；
     *  4. 语言页 / 登录页交叉淡出淡入，shader 背景共享不打断。
     *
     * 进程下次冷启时 [ceui.pixiv.i18n.AppLocalesBootstrap.syncAppCompatFromSavedTag] 会把
     * AppCompat per-app locale 也对齐到 MMKV，那次 set 发生在 Application.onCreate、没有 Activity
     * 在前台，AppCompat 的 lifecycle callback 不会触发 recreate。
     */
    private fun transitionToLogin() {
        greetingCycleJob?.cancel()

        AppLocales.saveTag(selectedTag)
        AppLocales.applyConfigurationInPlace(requireActivity(), selectedTag)

        relocalizeLoginPage()
        baseBind.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_shadow)
        crossFadeLanguagePageToLoginPage()
    }

    /**
     * 重新读 [R.string.*] 把登录页里已经 inflate 的 TextView 文本刷一遍。
     * 注意 click listener、checkbox observer 不要重绑 —— [setupLoginPage] 已经在 [initView] 跑过。
     */
    private fun relocalizeLoginPage() {
        val page = baseBind.loginPage
        page.loginButton.text = getString(R.string.now_login)
        page.signButton.text = getString(R.string.now_sign)
        page.restoreFromEmail.text = getString(R.string.email_backup_login_entry)
        // 协议链接里的 SpannableString 也是 inflate 时算的，要重塞 —— 内部 getString(...) 此刻
        // 已经走新 locale 了。
        setupTermsText(page.firstText)

        // Toolbar overflow 菜单（action_settings / action_import）的 title 是 inflate 那一刻烤
        // 进 MenuItem 的，光改 Configuration 不会刷新。清空重 inflate；setOnMenuItemClickListener
        // 挂在 Toolbar 上而不是 MenuItem 上，不需要重绑。
        baseBind.toolbar.menu.clear()
        inflateToolbarMenu()
    }

    private fun crossFadeLanguagePageToLoginPage() {
        val langPage = baseBind.languagePage.root
        val loginPage = baseBind.loginPage.root
        val dur = 380L

        loginPage.alpha = 0f
        loginPage.visibility = View.VISIBLE
        loginPage.animate().alpha(1f).setDuration(dur).start()

        langPage.animate().alpha(0f).setDuration(dur).withEndAction {
            langPage.visibility = View.GONE
        }.start()
    }

    /** 从登录页返回选语言页：反向交叉淡入淡出，并恢复问候语轮播。 */
    private fun backToLanguagePage() {
        val langPage = baseBind.languagePage.root
        val loginPage = baseBind.loginPage.root
        val dur = 300L

        baseBind.toolbar.navigationIcon = null

        langPage.visibility = View.VISIBLE
        langPage.alpha = 0f
        langPage.animate().alpha(1f).setDuration(dur).start()

        loginPage.animate().alpha(0f).setDuration(dur).withEndAction {
            loginPage.visibility = View.GONE
        }.start()

        startGreetingCycle()
    }

    // ── Login page ──

    private fun setupLoginPage() {
        val page = baseBind.loginPage

        page.loginButton.setOnClickListener {
            checkAndNext {
                openProxyHint { openOAuthTab(ceui.pixiv.login.PixivLogin.startLoginUrl()) }
            }
        }
        page.signButton.setOnClickListener {
            checkAndNext {
                openProxyHint { openOAuthTab(ceui.pixiv.login.PixivLogin.startSignUrl()) }
            }
        }

        // Google Play 渠道合规：邮箱备份/恢复会把用户邮箱传到 pixshaft-api，而数据安全表单
        // 未声明「电子邮件地址」收集（40760 被 Play 政策标记）。lite 渠道不提供该功能。
        // 此入口无需登录即可触达，是 Play 自动化测试检测到邮箱外传的位置。
        if (BuildConfig.IS_LITE) {
            page.restoreFromEmail.visibility = View.GONE
        } else {
            page.restoreFromEmail.setOnClickListener {
                startActivity(Intent(mContext, TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.EMAIL_BACKUP.key)
                    putExtra("mode", "restore")
                })
            }
        }

        setupTermsText(page.firstText)

        observeRefreshTokenLogin()

        viewModel.isChecked.observe(viewLifecycleOwner) { page.checkboxOne.isSelected = it }
        page.checkboxOne.setOnClickListener {
            viewModel.isChecked.value = !(viewModel.isChecked.value ?: false)
        }
    }

    private fun showRefreshTokenDialog() {
        // 上一个还挂着就先收掉：refreshTokenDialog 只记得最后一个，被顶掉的那个
        // 到 onDestroyView 没人 dismiss，就是一条 WindowLeaked。
        refreshTokenDialog?.dismiss()
        val builder = WitDialog.EditTextDialogBuilder(mContext)
        builder.setTitle(getString(R.string.refresh_token_dialog_title))
            .setPlaceholder(getString(R.string.refresh_token_dialog_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            .addAction(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(getString(R.string.refresh_token_dialog_positive)) { dialog, _ ->
                val token = builder.editText.text.toString().trim()
                if (token.isNotEmpty()) {
                    dialog.dismiss()
                    Common.showToast(getString(R.string.trying_login), 2)
                    viewModel.startRefreshTokenLogin(token)
                }
            }
        refreshTokenDialog = builder.create()
        refreshTokenDialog?.show()
    }

    /**
     * 登录终局的收尾。挂 viewLifecycleOwner 只是为了不在没有 View 的时候碰 UI —— 登录本身
     * 跑在 [LandingViewModel] 里，View 被重建也不会断，[Event] 会把结果交给新的 observer。
     */
    private fun observeRefreshTokenLogin() {
        viewModel.refreshTokenLoginEvent.observeEvent(viewLifecycleOwner) { outcome ->
            when (outcome) {
                is RefreshTokenLoginOutcome.Failed ->
                    Common.showToast(getString(outcome.messageRes), 3)

                is RefreshTokenLoginOutcome.Succeeded -> {
                    Common.showToast(getString(R.string.refresh_token_success_toast), 2)
                    MoonSync.syncFromCloudOnLogin(mActivity, outcome.uid) {
                        mActivity.finish()
                        Common.restart()
                    }
                }
            }
        }
    }

    private fun setupTermsText(textView: TextView) {
        textView.movementMethod = LinkMovementMethod.getInstance()
        val tos = getString(R.string.terms_of_service)
        val pp = getString(R.string.privacy_policy)
        textView.text = SpannableString(
            String.format(getString(R.string.landing_terms_base), tos, pp)
        ).apply {
            setLinkSpan(tos, hideUnderLine = false) {
                openWebPage(
                    "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios",
                    getString(R.string.pixiv_use_detail)
                )
            }
            setLinkSpan(pp, hideUnderLine = false) {
                openWebPage(
                    "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios",
                    getString(R.string.privacy)
                )
            }
        }
    }

    // ── Helpers ──

    private fun openWebPage(url: String, title: String) {
        startActivity(Intent(mContext, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key)
            putExtra(Params.URL, url)
            putExtra(Params.TITLE, title)
        })
    }

    private fun performLogin(userJson: String) {
        val exportUser = runCatching {
            Shaft.sGson.fromJson(userJson, AccountResponse::class.java)
        }.getOrNull()
        if (exportUser?.user == null) {
            Common.showToast("账号信息格式不正确，导入失败", 3)
            return
        }
        Local.persistLoggedInUser(exportUser)
        Common.showToast("导入成功", 2)
        startActivity(Intent(mContext, MainActivity::class.java))
        mActivity.finish()
    }

    private fun openOAuthTab(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            Common.showToast("未找到浏览器")
        }
    }

    private fun openProxyHint(onConfirm: () -> Unit) {
        val dialog = MessageDialogBuilder(mContext)
            .setTitle(getString(R.string.string_143))
            .setMessage(getString(R.string.string_360))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.string_361)) { d, _ ->
                onConfirm()
                d.dismiss()
            }
            .create()
        dialog.window?.setWindowAnimations(R.style.dialog_animation_scale)
        dialog.show()
    }

    private fun checkAndNext(block: () -> Unit) {
        if (viewModel.isChecked.value == true) {
            block()
        } else {
            Toaster.showShort(getString(R.string.read_agreement))
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class Greeting(val tag: String, val hero: String, val subtitle: String)

    companion object {
        /**
         * 声明了通配 http/https（handleAllWebDataURI 筛不掉）、但并非浏览器的
         * 劫持类 App，按 activity 全名点名排除。
         */
        private val HIJACKER_ACTIVITY_NAMES = setOf(
            "com.taobao.browser.BrowserActivity", // 淘宝
            "com.taobao.live.h5.BrowserActivityH", // 淘宝
            "com.taobao.live.h5.TransparentWebViewActivity", // 淘宝
            "com.jingdong.app.mall.open.BrowserActivity", // 京东
            "com.taobao.live.h5.BrowserActivity", // 淘宝特价版
            "com.litetao.app.MNWebActivity", // 淘宝特价版
            "com.xunlei.downloadprovider.launch.LaunchActivity2", // 迅雷
            "com.tmall.wireless.splash.SchemeHandlerActivity", // 天猫
            "com.tencent.hunyuan.app.chat.biz.openfile.ExternalOpeFileActivity", // 腾讯元宝
            "com.baidu.searchbox.BoxBrowserActivity", // 百度
            "com.UCMobile.main.UCMobile.DefaultBrowserEntry", // UC浏览器
            "com.yxcorp.gifshow.growth.applink.GrowthAppLinkActivityHttpRecommend", // 快手极速版
        )

        /** 常用「纯浏览器」关键词，选择列表里置顶。 */
        private val PRIORITY_BROWSER_KEYWORDS = listOf("chrome", "via", "firefox")
    }
}

fun SpannableString.setLinkSpan(
    text: String,
    hideUnderLine: Boolean = true,
    color: Int? = null,
    action: () -> Unit
) {
    val textIndex = indexOf(text)
    if (textIndex >= 0) {
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = action()
                override fun updateDrawState(ds: TextPaint) {
                    color?.let { ds.linkColor = it }
                    if (hideUnderLine) {
                        ds.color = ds.linkColor
                        ds.isUnderlineText = false
                    } else {
                        super.updateDrawState(ds)
                    }
                }
            },
            textIndex,
            textIndex + text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
