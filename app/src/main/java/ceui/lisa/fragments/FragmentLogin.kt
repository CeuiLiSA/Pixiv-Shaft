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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        // Tunnel background: hidden until atlas ready, then fade in + hide spinner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            baseBind.tunnelBackground.alpha = 0f
            baseBind.tunnelBackground.onReadyListener = {
                baseBind.tunnelBackground.animate()
                    .alpha(1f)
                    .setDuration(1200)
                    .start()
                baseBind.loadingSpinner.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { baseBind.loadingSpinner.visibility = View.GONE }
                    .start()
            }
        } else {
            baseBind.loadingSpinner.visibility = View.GONE
        }

        if (AppLocales.hasUserConfigured) {
            baseBind.languagePage.root.visibility = View.GONE
            baseBind.loginPage.root.visibility = View.VISIBLE
        } else {
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

    private fun transitionToLogin() {
        greetingCycleJob?.cancel()

        val langRoot = baseBind.languagePage.root
        val loginRoot = baseBind.loginPage.root
        val offset = dp(60f).toFloat()

        loginRoot.alpha = 0f
        loginRoot.translationY = offset
        loginRoot.visibility = View.VISIBLE

        langRoot.animate()
            .alpha(0f)
            .translationY(-offset)
            .setDuration(350)
            .setInterpolator(AccelerateInterpolator())
            .start()

        loginRoot.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(100)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                langRoot.visibility = View.GONE
                // Apply locale AFTER animation — setApplicationLocales() triggers
                // Activity recreation, calling it earlier kills the animation.
                AppLocales.apply(selectedTag)
            }
            .start()
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
        val exportUser = Shaft.sGson.fromJson(userJson, UserModel::class.java)
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
            Toast.makeText(requireContext(), getString(R.string.read_agreement), Toast.LENGTH_SHORT)
                .show()
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
