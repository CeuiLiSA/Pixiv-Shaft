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
import ceui.lisa.databinding.FragmentLandingBinding
import ceui.lisa.feature.HostManager
import ceui.lisa.helper.LanguageHelper
import ceui.loxia.findLanguageBySystem
import ceui.loxia.openChromeTab
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick

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
            pushFragment(
                R.id.navigation_select_language,
            )
        }

        binding.logIn.setOnClick {
            val baseUrl = HostManager.get().loginUrl
            val finalUri = baseUrl.toUri().buildUpon()
                .appendQueryParameter(
                    "lang",
                    LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage().split("-")[0]
                )
                .build()

            requireContext().openChromeTab(finalUri.toString())
        }

        binding.logIn.setOnLongClickListener {
            pushFragment(R.id.navigation_login_with_token)
            true
        }

        binding.register.setOnClick {
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

    private val WELCOME_MESSAGES = arrayOf(
        "欢迎使用",         // 简体中文
        "ようこそ",         // 日本語
        "Welcome",         // English
        "歡迎使用",         // 繁體中文
        "Добро пожаловать", // русский
        "환영합니다"         // 한국어
    )
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