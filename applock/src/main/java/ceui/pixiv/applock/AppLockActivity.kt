package ceui.pixiv.applock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.iconTile
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.setTextWithIcon
import ceui.pixiv.witstudio.R as WitR

/**
 * 锁屏页：盖在宿主页面上，验证通过后 finish 露出原页面。
 *
 * 版式按 V3「设置 / 账户」的安静表面：中性底 → 17/17/17/7 图标容器 → 28sp 标题 → 说明 →
 * 唯一的实色胶囊主操作。系统验证弹窗本身由 BiometricPrompt 绘制，这里只负责弹窗之外的部分。
 */
internal class AppLockActivity : AppCompatActivity() {

    private lateinit var prompt: BiometricPrompt
    private lateinit var status: TextView

    /** 已经自动弹过验证的 episode，见 [AppLockSession.episode]。转屏时随存档保留。 */
    private var promptedEpisode = 0L

    private val session: AppLockSession get() = AppLock.session

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLock.themeApplier.applyTo(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        promptedEpisode = savedInstanceState?.getLong(KEY_PROMPTED_EPISODE) ?: 0L

        // 官方要求在 onCreate 里创建：转屏后弹窗由库自己恢复，结果仍回到这个回调。
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), authCallback)

        setContentView(buildContent(animate = savedInstanceState == null))

        // 锁定期间返回键只把 App 退到后台，不能 finish 露出底下的页面。
        // 不带 LifecycleOwner：锁屏页没有 Fragment 回调要让位，生命周期与 Activity 相同。
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 兜底：解锁时已统一 finish 所有锁屏页，这里只防漏网的实例露在已解锁的 App 上。
        if (!session.isLocked) {
            finish()
            return
        }
        val episode = session.episode
        if (episode != promptedEpisode && !session.isAuthenticating) {
            promptedEpisode = episode
            authenticate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_PROMPTED_EPISODE, promptedEpisode)
    }

    override fun onDestroy() {
        // 转屏时验证仍在进行（库会恢复弹窗）；其余情况弹窗已随页面结束。
        if (!isChangingConfigurations) session.isAuthenticating = false
        super.onDestroy()
    }

    private fun authenticate() {
        if (Authenticators.availability(this) != AppLockAvailability.AVAILABLE) {
            unlockAndFinish()
            return
        }
        showStatus(null)
        session.isAuthenticating = true
        val appName = applicationInfo.loadLabel(packageManager)
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.applock_prompt_title, appName))
                .setSubtitle(getString(R.string.applock_prompt_subtitle))
                .setAllowedAuthenticators(Authenticators.ALLOWED)
                // 面部等被动识别通过后直接放行，不再多点一次「确认」。
                .setConfirmationRequired(false)
                .build(),
        )
    }

    private val authCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            unlockAndFinish()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            session.isAuthenticating = false
            when (errorCode) {
                // 用户主动取消、或页面切走时系统收起弹窗：留在锁屏页，下一步就是「解锁」按钮。
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                BiometricPrompt.ERROR_CANCELED -> showStatus(null)
                else -> if (Authenticators.availability(this@AppLockActivity) != AppLockAvailability.AVAILABLE) {
                    unlockAndFinish()
                } else {
                    // 次数过多等：系统给的文案已本地化，原样告诉用户。
                    showStatus(errString)
                }
            }
        }
    }

    private fun unlockAndFinish() {
        AppLock.unlock()
    }

    private fun showStatus(text: CharSequence?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildContent(animate: Boolean): View {
        val appName = applicationInfo.loadLabel(packageManager)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        column.addView(
            iconTile(R.drawable.applock_ic_lock, size = 72),
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        column.addView(
            label(getString(R.string.applock_locked_title, appName), 28f, 700).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(MAX_TEXT_WIDTH_DP)
                lineHeightRatio(1.3f)
                ViewCompat.setAccessibilityHeading(this, true)
            },
            wrap().apply { topMargin = dp(24) },
        )
        column.addView(
            label(getString(R.string.applock_locked_desc), 15f, 400, color(WitR.color.wit_text_2)).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(MAX_TEXT_WIDTH_DP)
                lineHeightRatio(1.7f)
            },
            wrap().apply { topMargin = dp(12) },
        )
        column.addView(
            pillButton(getString(R.string.applock_unlock)) {
                if (!session.isAuthenticating) authenticate()
            }.apply {
                textSize = 16f
                minHeight = dp(56)
                minWidth = dp(240)
                // 比文字宽的胶囊：图标走行内 span 跟文字一起居中，compound drawable 会钉在左缘。
                setTextWithIcon(getString(R.string.applock_unlock), R.drawable.applock_ic_fingerprint, sizeDp = 20)
            },
            wrap().apply { topMargin = dp(32) },
        )
        status = label("", 13f, 400, color(WitR.color.wit_text_2)).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(MAX_TEXT_WIDTH_DP)
            lineHeightRatio(1.6f)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(status, wrap().apply { topMargin = dp(16) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(WitR.color.wit_bg))
            addView(
                FrameLayout(context).apply {
                    addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                },
                ViewGroup.LayoutParams(-1, -1),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        if (animate && motionEnabled()) {
            column.alpha = 0f
            column.translationY = dpF(7f)
            column.animate().alpha(1f).translationY(0f).setDuration(350).start()
        }
        return scroll
    }

    private fun wrap() = LinearLayout.LayoutParams(-2, -2)

    companion object {
        private const val KEY_PROMPTED_EPISODE = "applock_prompted_episode"
        private const val MAX_TEXT_WIDTH_DP = 400

        /** 无动画盖上：滑入动画会让底下的页面露出半屏。 */
        fun intent(context: Context): Intent =
            Intent(context, AppLockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
