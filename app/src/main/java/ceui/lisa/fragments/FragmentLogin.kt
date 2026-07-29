package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.lisa.databinding.ActivityLoginBinding
import ceui.lisa.databinding.ItemLanguageRowBinding
import ceui.lisa.models.UserModel
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.pixiv.i18n.AppLocales
import com.hjq.toast.Toaster
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class LandingViewModel : ViewModel() {
    val isChecked = MutableLiveData(false)
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
        baseBind.toolbar.inflateMenu(R.menu.login_menu)
        baseBind.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(mContext, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置")
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

                else -> false
            }
        }
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
        baseBind.toolbar.inflateMenu(R.menu.login_menu)
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
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "邮箱备份")
                    putExtra("mode", "restore")
                })
            }
        }

        setupTermsText(page.firstText)

        viewModel.isChecked.observe(viewLifecycleOwner) { page.checkboxOne.isSelected = it }
        page.checkboxOne.setOnClickListener {
            viewModel.isChecked.value = !(viewModel.isChecked.value ?: false)
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
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            putExtra(Params.URL, url)
            putExtra(Params.TITLE, title)
        })
    }

    private fun performLogin(userJson: String) {
        val exportUser = runCatching {
            Shaft.sGson.fromJson(userJson, UserModel::class.java)
        }.getOrNull()
        if (exportUser?.user == null) {
            Common.showToast("账号信息格式不正确，导入失败", 3)
            return
        }
        Local.saveUser(exportUser)
        Dev.refreshUser = true
        UserEntity().apply {
            loginTime = System.currentTimeMillis()
            userID = exportUser.user.id
            userGson = Shaft.sGson.toJson(Local.getUser())
            AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(this)
        }
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
            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
