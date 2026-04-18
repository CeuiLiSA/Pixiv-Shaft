package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.lisa.databinding.ActivityLoginBinding
import ceui.lisa.interfaces.FeedBack
import ceui.lisa.models.UserModel
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.pixiv.i18n.AppLocales
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder
import java.util.Locale
import kotlin.math.roundToInt

class LandingViewModel : ViewModel() {
    val isChecked = MutableLiveData(false)
}

class FragmentLogin : BaseFragment<ActivityLoginBinding>() {

    private val viewModel: LandingViewModel by viewModels()

    // ── Language page state ──
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
    private val rowByTag = mutableMapOf<String, RowViews>()
    private val cycleHandler = Handler(Looper.getMainLooper())
    private var cycleIndex = 0
    private var greetingHero: TextView? = null
    private var greetingSubtitle: TextView? = null
    private var continueBtn: TextView? = null
    private var bottomLinear: LinearLayout? = null
    private var safeTop = 0
    private var safeBottom = 0

    private val cycleRunnable = object : Runnable {
        override fun run() {
            cycleIndex = (cycleIndex + 1) % greetings.size
            fadeGreetingTo(greetings[cycleIndex])
            cycleHandler.postDelayed(this, 2200L)
        }
    }

    // ── Lifecycle ──

    public override fun initLayout() {
        mLayoutID = R.layout.activity_login
    }

    public override fun initView() {
        val needsLanguage = !AppLocales.hasUserConfigured
        val startPage = if (needsLanguage) PAGE_LANGUAGE else PAGE_LOGIN

        baseBind.landingPager.adapter = LandingPagerAdapter(needsLanguage)
        baseBind.landingPager.setCurrentItem(startPage, false)
        baseBind.landingPager.isUserInputEnabled = false

        // Safe-area insets — handle at fragment level because ViewPager2's
        // internal RecyclerView does NOT dispatch insets to page item views.
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeTop = bars.top
            safeBottom = bars.bottom
            baseBind.toolbar.setPadding(0, bars.top, 0, 0)
            applyPageInsets()
            insets
        }
        baseBind.toolbar.inflateMenu(R.menu.login_menu)
        baseBind.toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置")
                startActivity(intent)
                return@OnMenuItemClickListener true
            } else if (item.itemId == R.id.action_import) {
                val userJson = ClipBoardUtils.getClipboardContent(mContext)
                if (userJson != null && !TextUtils.isEmpty(userJson)
                    && userJson.contains(Params.USER_KEY)
                ) {
                    performLogin(userJson)
                } else {
                    Common.showToast("剪贴板无用户信息", 3)
                }
                return@OnMenuItemClickListener true
            }
            false
        })
    }

    override fun initData() {}

    override fun onStart() {
        super.onStart()
        if (baseBind.landingPager.currentItem == PAGE_LANGUAGE) {
            cycleHandler.postDelayed(cycleRunnable, 2200L)
        }
    }

    override fun onStop() {
        super.onStop()
        cycleHandler.removeCallbacks(cycleRunnable)
    }

    // ── ViewPager adapter ──

    private inner class LandingPagerAdapter(
        private val hasLanguagePage: Boolean
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = if (hasLanguagePage) 2 else 1

        override fun getItemViewType(position: Int): Int {
            return if (hasLanguagePage) position else PAGE_LOGIN
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                PAGE_LANGUAGE -> {
                    val v = layoutInflater.inflate(R.layout.page_language_selection, parent, false)
                    object : RecyclerView.ViewHolder(v) {}
                }

                else -> {
                    val v = layoutInflater.inflate(R.layout.page_login, parent, false)
                    object : RecyclerView.ViewHolder(v) {}
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val type = getItemViewType(position)
            if (type == PAGE_LANGUAGE) {
                bindLanguagePage(holder.itemView)
            } else {
                bindLoginPage(holder.itemView)
            }
        }
    }

    private fun applyPageInsets() {
        greetingHero?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = dp(20f) + safeTop
        }
        continueBtn?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = dp(20f) + safeBottom
        }
        bottomLinear?.let {
            it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, safeBottom)
        }
    }

    // ── Page 0: Language selection ──

    private fun bindLanguagePage(root: View) {
        greetingHero = root.findViewById(R.id.greeting_hero)
        greetingSubtitle = root.findViewById(R.id.greeting_subtitle)
        continueBtn = root.findViewById(R.id.continue_button)
        val rows = root.findViewById<LinearLayout>(R.id.rows_container)

        selectedTag = matchSystemOrFallback()
        cycleIndex = greetings.indexOfFirst { it.tag == selectedTag }.coerceAtLeast(0)
        applyGreeting(greetings[cycleIndex])

        buildRows(rows)
        applyContinueLabel()

        // Apply safe-area insets (values come from fragment-level listener)
        applyPageInsets()

        continueBtn?.setOnClickListener {
            AppLocales.apply(selectedTag)
            baseBind.landingPager.setCurrentItem(PAGE_LOGIN, true)
            cycleHandler.removeCallbacks(cycleRunnable)
        }
    }

    private fun buildRows(container: LinearLayout) {
        container.removeAllViews()
        rowByTag.clear()

        AppLocales.supportedTags.forEachIndexed { idx, tag ->
            val row = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), 0, dp(20f), 0)
                minimumHeight = dp(52f)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    null,
                    ColorDrawable(Color.WHITE),
                )
                setOnClickListener { onRowSelected(tag) }
            }
            val label = TextView(container.context).apply {
                text = AppLocales.displayName(tag)
                textSize = 16f
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, 0xCC000000.toInt())
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val check = TextView(container.context).apply {
                text = "✓"
                textSize = 20f
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, 0xCC000000.toInt())
                alpha = if (tag == selectedTag) 1f else 0f
            }
            row.addView(
                check, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            container.addView(
                row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)
                )
            )

            if (idx < AppLocales.supportedTags.lastIndex) {
                val divider = View(container.context).apply { setBackgroundColor(0x33FFFFFF) }
                container.addView(
                    divider, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f).coerceAtLeast(1)
                    )
                )
            }

            rowByTag[tag] = RowViews(row, check)
        }
    }

    private fun onRowSelected(tag: String) {
        if (tag == selectedTag) return
        rowByTag[selectedTag]?.check?.animate()?.alpha(0f)?.setDuration(160L)?.start()
        rowByTag[tag]?.check?.animate()?.alpha(1f)?.setDuration(160L)?.start()
        selectedTag = tag
        cycleIndex = greetings.indexOfFirst { it.tag == tag }.coerceAtLeast(0)
        fadeGreetingTo(greetings[cycleIndex])
        applyContinueLabel()
    }

    private fun applyContinueLabel() {
        continueBtn?.text = when (selectedTag) {
            "zh-CN" -> "继续"
            "zh-TW" -> "繼續"
            "ja" -> "続ける"
            "ko" -> "계속"
            "ru" -> "Продолжить"
            "tr" -> "Devam"
            else -> "Continue"
        }
    }

    private fun applyGreeting(g: Greeting) {
        greetingHero?.text = g.hero
        greetingSubtitle?.text = g.subtitle
    }

    private fun fadeGreetingTo(g: Greeting) {
        greetingSubtitle?.animate()?.alpha(0f)?.setDuration(180L)?.start()
        greetingHero?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
            applyGreeting(g)
            greetingHero?.animate()?.alpha(1f)?.setDuration(260L)?.start()
            greetingSubtitle?.animate()?.alpha(0.75f)?.setDuration(260L)?.start()
        }?.start()
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

    // ── Page 1: Login ──

    private fun bindLoginPage(root: View) {
        bottomLinear = root.findViewById(R.id.bottom_linear)
        val loginBtn = root.findViewById<TextView>(R.id.login_button)
        val signBtn = root.findViewById<TextView>(R.id.sign_button)
        val checkboxOne = root.findViewById<LinearLayout>(R.id.checkbox_one)
        val firstText = root.findViewById<TextView>(R.id.first_text)

        // Apply safe-area insets (values come from fragment-level listener)
        applyPageInsets()

        loginBtn.setOnClickListener {
            checkAndNext {
                openProxyHint {
                    openOAuthTab(ceui.pixiv.login.PixivLogin.startLoginUrl())
                }
            }
        }
        signBtn.setOnClickListener {
            checkAndNext {
                openProxyHint {
                    openOAuthTab(ceui.pixiv.login.PixivLogin.startSignUrl())
                }
            }
        }

        firstText.movementMethod = LinkMovementMethod.getInstance()
        val matchTOS = getString(R.string.terms_of_service)
        val matchPP = getString(R.string.privacy_policy)
        val terms = String.format(getString(R.string.landing_terms_base), matchTOS, matchPP)
        firstText.text = SpannableString(terms).apply {
            this.setLinkSpan(matchTOS, hideUnderLine = false) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(
                    Params.URL,
                    "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios"
                )
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_use_detail))
                startActivity(intent)
            }
            this.setLinkSpan(matchPP, hideUnderLine = false) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(
                    Params.URL,
                    "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios"
                )
                intent.putExtra(Params.TITLE, getString(R.string.privacy))
                startActivity(intent)
            }
        }

        viewModel.isChecked.observe(viewLifecycleOwner) {
            checkboxOne.isSelected = it
        }
        checkboxOne.setOnClickListener {
            viewModel.isChecked.value = !(viewModel.isChecked.value ?: false)
        }
    }

    // ── Shared helpers ──

    private fun performLogin(userJson: String) {
        val exportUser = Shaft.sGson.fromJson(userJson, UserModel::class.java)
        Local.saveUser(exportUser)
        Dev.refreshUser = true
        val userEntity = UserEntity()
        userEntity.loginTime = System.currentTimeMillis()
        userEntity.userID = exportUser.user.id
        userEntity.userGson = Shaft.sGson.toJson(Local.getUser())
        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity)
        Common.showToast("导入成功", 2)
        val intent = Intent(mContext, MainActivity::class.java)
        MainActivity.newInstance(intent, mContext)
        mActivity.finish()
    }

    private fun openOAuthTab(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            Common.showToast("未找到浏览器")
        }
    }

    private fun openProxyHint(feedBack: FeedBack) {
        val qmuiDialog = MessageDialogBuilder(mContext)
            .setTitle(mContext.getString(R.string.string_143))
            .setMessage(mContext.getString(R.string.string_360))
            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
            .addAction(mContext.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(mContext.getString(R.string.string_361)) { dialog, _ ->
                feedBack.doSomething()
                dialog.dismiss()
            }
            .create()
        qmuiDialog.window?.setWindowAnimations(R.style.dialog_animation_scale)
        qmuiDialog.show()
    }

    private fun checkAndNext(block: () -> Unit) {
        if (viewModel.isChecked.value == true) {
            block()
        } else {
            Toast.makeText(requireContext(), getString(R.string.read_agreement), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private data class Greeting(val tag: String, val hero: String, val subtitle: String)
    private data class RowViews(val container: LinearLayout, val check: TextView)

    companion object {
        private const val PAGE_LANGUAGE = 0
        private const val PAGE_LOGIN = 1
    }
}

fun SpannableString.setLinkSpan(
    text: String,
    hideUnderLine: Boolean = true,
    color: Int? = null,
    action: () -> Unit
) {
    val textIndex = this.indexOf(text)
    if (textIndex >= 0) {
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    action()
                }

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
