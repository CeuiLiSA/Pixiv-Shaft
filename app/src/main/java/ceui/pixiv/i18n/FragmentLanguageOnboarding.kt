package ceui.pixiv.i18n

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import ceui.lisa.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 首次冷启（[AppLocales.hasUserConfigured] 为 false）时在登录前插入的语言选择页。
 *
 * - 背景用 `TracedTunnelView` 无限回廊 shader + 登录页同款 `login_scrim_gradient`，视觉连续。
 * - 顶部 hero 文字每 2.2s 切到下一个语言的本地问候语（Welcome → 欢迎 → ようこそ → …），180ms fade。
 * - 语言列表用一行一行的行式选项，ripple 反馈，右侧 ✓ 表示当前选中。
 * - 底部 Continue 按钮文案随选中语言切换；点击 = [AppLocales.apply]，AppCompat 会触发 Activity 重建，
 *   `TemplateActivity` 二次路由 "登录注册" 时 `hasUserConfigured=true`，直接进登录页。
 */
class FragmentLanguageOnboarding : Fragment(R.layout.fragment_language_onboarding) {

    private val greetings: List<Greeting> = listOf(
        Greeting(tag = "en", hero = "Welcome", subtitle = "Choose your language"),
        Greeting(tag = "zh-CN", hero = "欢迎", subtitle = "选择你的语言"),
        Greeting(tag = "zh-TW", hero = "歡迎", subtitle = "選擇你的語言"),
        Greeting(tag = "ja", hero = "ようこそ", subtitle = "言語を選んでください"),
        Greeting(tag = "ko", hero = "환영합니다", subtitle = "언어를 선택하세요"),
        Greeting(tag = "ru", hero = "Добро пожаловать", subtitle = "Выберите язык"),
        Greeting(tag = "tr", hero = "Hoş geldiniz", subtitle = "Dilinizi seçin"),
    )

    private var selectedTag: String = "en"
    private val rowByTag = mutableMapOf<String, Row>()

    private val cycleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cycleIndex = 0
    private lateinit var greetingHero: TextView
    private lateinit var greetingSubtitle: TextView
    private lateinit var continueBtn: TextView

    private val cycleRunnable = object : Runnable {
        override fun run() {
            cycleIndex = (cycleIndex + 1) % greetings.size
            fadeGreetingTo(greetings[cycleIndex])
            cycleHandler.postDelayed(this, CYCLE_INTERVAL_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        greetingHero = view.findViewById(R.id.greeting_hero)
        greetingSubtitle = view.findViewById(R.id.greeting_subtitle)
        continueBtn = view.findViewById(R.id.continue_button)
        val rows = view.findViewById<LinearLayout>(R.id.rows_container)

        selectedTag = matchSystemOrFallback()
        cycleIndex = greetings.indexOfFirst { it.tag == selectedTag }.coerceAtLeast(0)
        applyGreeting(greetings[cycleIndex])

        buildRows(rows)
        applyContinueLabel()

        applyInsets(view)

        continueBtn.setOnClickListener {
            AppLocales.apply(selectedTag)
            // AppCompat 会自动触发 Activity 重建。
        }
    }

    private fun applyInsets(root: View) {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()

        // 保留 XML 里的原始边距，再和 inset 相加，不要互相覆盖。
        val continueBottomBase = dp(48f)
        val continueHorizontalBase = dp(30f)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // Hero 顶部让开 status bar / 刘海
            greetingHero.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top
            }
            // Continue 底部让开导航栏 / 手势条；左右让开横屏刘海
            continueBtn.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = continueBottomBase + bars.bottom
                leftMargin = continueHorizontalBase + bars.left
                rightMargin = continueHorizontalBase + bars.right
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onStart() {
        super.onStart()
        cycleHandler.postDelayed(cycleRunnable, CYCLE_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        cycleHandler.removeCallbacks(cycleRunnable)
    }

    private fun matchSystemOrFallback(): String {
        val sys = Locale.getDefault()
        val exact = AppLocales.supportedTags.firstOrNull {
            val l = Locale.forLanguageTag(it)
            l.language == sys.language && l.country.equals(sys.country, ignoreCase = true)
        }
        if (exact != null) return exact
        val byLang = AppLocales.supportedTags.firstOrNull {
            Locale.forLanguageTag(it).language == sys.language
        }
        return byLang ?: "en"
    }

    private fun buildRows(container: LinearLayout) {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()

        container.removeAllViews()
        rowByTag.clear()

        AppLocales.supportedTags.forEachIndexed { idx, tag ->
            val row = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), 0, dp(20f), 0)
                minimumHeight = dp(56f)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    null,
                    ColorDrawable(Color.TRANSPARENT),
                )
                setOnClickListener { onRowSelected(tag) }
            }
            val label = TextView(container.context).apply {
                text = AppLocales.displayName(tag)
                textSize = 17f
                setTextColor(Color.WHITE)
                setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, SHADOW_COLOR)
            }
            val labelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label, labelLp)

            val check = TextView(container.context).apply {
                text = "✓"
                textSize = 20f
                setTextColor(Color.WHITE)
                setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, SHADOW_COLOR)
                alpha = if (tag == selectedTag) 1f else 0f
            }
            row.addView(
                check,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val rowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56f),
            )
            container.addView(row, rowLp)

            if (idx < AppLocales.supportedTags.lastIndex) {
                val divider = View(container.context).apply {
                    setBackgroundColor(0x33FFFFFF)
                }
                container.addView(
                    divider,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f).coerceAtLeast(1)),
                )
            }

            rowByTag[tag] = Row(row, check)
        }
    }

    private fun onRowSelected(tag: String) {
        if (tag == selectedTag) return
        val prev = rowByTag[selectedTag]?.check
        val next = rowByTag[tag]?.check
        selectedTag = tag

        prev?.animate()?.alpha(0f)?.setDuration(160L)?.start()
        next?.animate()?.alpha(1f)?.setDuration(160L)?.start()

        cycleIndex = greetings.indexOfFirst { it.tag == tag }.coerceAtLeast(0)
        fadeGreetingTo(greetings[cycleIndex])
        applyContinueLabel()
    }

    private fun applyContinueLabel() {
        continueBtn.text = continueLabelFor(selectedTag)
    }

    private fun continueLabelFor(tag: String): String = when (tag) {
        "en" -> "Continue"
        "zh-CN" -> "继续"
        "zh-TW" -> "繼續"
        "ja" -> "続ける"
        "ko" -> "계속"
        "ru" -> "Продолжить"
        "tr" -> "Devam"
        else -> "Continue"
    }

    private fun applyGreeting(g: Greeting) {
        greetingHero.text = g.hero
        greetingSubtitle.text = g.subtitle
    }

    private fun fadeGreetingTo(g: Greeting) {
        greetingSubtitle.animate().alpha(0f).setDuration(180L).start()
        greetingHero.animate().alpha(0f).setDuration(180L).withEndAction {
            applyGreeting(g)
            greetingHero.animate().alpha(1f).setDuration(260L).start()
            greetingSubtitle.animate().alpha(0.75f).setDuration(260L).start()
        }.start()
    }

    private data class Greeting(val tag: String, val hero: String, val subtitle: String)
    private data class Row(val container: LinearLayout, val check: TextView)

    companion object {
        private const val CYCLE_INTERVAL_MS = 2200L
        // shader 背景亮暗不定，给每个 row 的文字加一圈深色阴影保证可读。
        private const val SHADOW_RADIUS = 10f
        private const val SHADOW_DY = 2f
        private const val SHADOW_COLOR = 0xCC000000.toInt()
    }
}
