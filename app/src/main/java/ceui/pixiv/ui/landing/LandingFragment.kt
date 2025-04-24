package ceui.pixiv.ui.landing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentLandingBinding
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick

class LandingFragment : PixivFragment(R.layout.fragment_landing) {
    private val binding by viewBinding(FragmentLandingBinding::bind)
    private val landingViewModel by viewModels<LandingViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val label = binding.welcomeLabel
        // 初始化欢迎语
        landingViewModel.currentIndex.observe(viewLifecycleOwner) { index ->
            label.fadeToNextMessage(WELCOME_MESSAGES[index])
        }

        binding.start.setOnClick {
            pushFragment(R.id.navigation_language_picker)
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


fun TextView.fadeToNextMessage(textStr: String) {
    // 先执行淡出和上移动画
    animate()
        .alpha(0f)
        .translationY(-30f) // 向上移动一点
        .setDuration(300)
        .withEndAction {
            // 切换文本，并把视图设置为稍微下方和透明
            text = textStr
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