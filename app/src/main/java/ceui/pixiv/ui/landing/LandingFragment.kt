package ceui.pixiv.ui.landing

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentLandingBinding
import ceui.lisa.feature.HostManager
import ceui.lisa.helper.LanguageHelper
import ceui.loxia.launchSuspend
import ceui.loxia.openChromeTab
import ceui.loxia.openClashApp
import ceui.loxia.pushFragmentForResult
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import java.util.Locale

class LandingFragment : PixivFragment(R.layout.fragment_landing) {
    private val binding by viewBinding(FragmentLandingBinding::bind)
    private val landingViewModel by constructVM({
        findLanguageBySystem()
    }) { language ->
        LandingViewModel(language)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomLayout.updatePadding(bottom = insets.bottom + 12.ppppx)
            binding.languageLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            windowInsets
        }

        val label = binding.welcomeLabel
        // 初始化欢迎语
        landingViewModel.currentIndex.observe(viewLifecycleOwner) { index ->
            label.fadeToNextMessage(WELCOME_MESSAGES[index])
        }

        landingViewModel.chosenLanguage.observe(viewLifecycleOwner) { name ->
            binding.languageName.text = name
        }
        binding.languageLayout.setOnClick {
            pushFragmentForResult<String>(R.id.navigation_language_picker) { languageName ->
                landingViewModel.updateLanguage(languageName)
            }
        }

        binding.logIn.setOnClick {
            checkVPNAndNext {
                val baseUrl = HostManager.get().loginUrl
                val finalUri = baseUrl.toUri().buildUpon()
                    .appendQueryParameter(
                        "lang",
                        LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage().split("-")[0]
                    )
                    .build()

                requireContext().openChromeTab(finalUri.toString())
            }
        }

        binding.register.setOnClick {
            checkVPNAndNext {
                val baseUrl = HostManager.get().signupUrl
                val finalUri = baseUrl.toUri().buildUpon()
                    .appendQueryParameter(
                        "lang",
                        LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage().split("-")[0]
                    )
                    .build()

                requireContext().openChromeTab(finalUri.toString())
            }
        }
    }


    private fun checkVPNAndNext(block: () -> Unit) {
        val context = requireContext()
        if (requireNetworkStateManager().canAccessGoogle.value == true) {
            block()
        } else {
            launchSuspend {
                if (alertYesOrCancel("请打开VPN后继续")) {
                    openClashApp(context)
                }
            }
        }
    }

    private fun findLanguageBySystem(): String {
        val inSettings = Shaft.sSettings.appLanguage
        if (inSettings?.isNotEmpty() == true && inSettings != "undefined") {
            return inSettings
        }

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag() // 例如 zh-CN, ja-JP, en-US

        // 先尝试全匹配
        LANGUAGE_MAP.entries.firstOrNull {
            it.value.equals(languageTag, ignoreCase = true)
        }?.let {
            return it.key
        }

        // 再尝试只匹配语言部分 (zh, ja, en...)
        LANGUAGE_MAP.entries.firstOrNull {
            it.value.substringBefore('-').equals(locale.language, ignoreCase = true)
        }?.let {
            return it.key
        }

        // 如果都匹配不上，默认返回 English
        return "English"
    }


    private val WELCOME_MESSAGES = arrayOf(
        "欢迎使用",         // 简体中文
        "ようこそ",         // 日本語
        "Welcome",         // English
        "歡迎使用",         // 繁體中文
        "Добро пожаловать", // русский
        "환영합니다"         // 한국어
    )

    private val LANGUAGE_MAP = mapOf(
        "简体中文" to "zh-CN",
        "日本語" to "ja",
        "English" to "en",
        "繁體中文" to "zh-TW",
        "русский" to "ru",
        "한국어" to "ko"
    )


    companion object {
        const val PURPOSE_LOGIN = 1
        const val PURPOSE_REGISTER = 2
    }
}


fun TextView.fadeToNextMessage(textStr: String? = null) {
    // 先执行淡出和上移动画
    animate()
        .alpha(0f)
        .translationY(-30f) // 向上移动一点
        .setDuration(300)
        .withEndAction {
            // 切换文本，并把视图设置为稍微下方和透明
            if (textStr != null) {
                text = textStr
            }
            translationY = 30f // 初始在下方
            alpha = 0f

            // 再执行淡入和上移动画
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()
        }
        .start()
}