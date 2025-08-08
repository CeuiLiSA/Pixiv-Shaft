package ceui.pixiv.ui.landing

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LandingViewModel(private val language: String) : ViewModel() {

    private val _currentIndex = MutableLiveData<Int>(0)
    val currentIndex: LiveData<Int> get() = _currentIndex

    private val _chosenLanguage = MutableLiveData<String>(language)
    val chosenLanguage: LiveData<String> = _chosenLanguage

    private val handler = Handler(Looper.getMainLooper())
    private val switchInterval: Long = 5000L // 5 seconds

    private val switchRunnable = object : Runnable {
        override fun run() {
            _currentIndex.value = (_currentIndex.value!! + 1) % WELCOME_MESSAGES.size
            handler.postDelayed(this, switchInterval)
        }
    }

    init {
        handler.postDelayed(switchRunnable, switchInterval)
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacks(switchRunnable)
    }

    private val WELCOME_MESSAGES = arrayOf(
        "欢迎使用",         // 简体中文
        "ようこそ",         // 日本語
        "Welcome",         // English
        "歡迎使用",         // 繁體中文
        "Добро пожаловать", // русский
        "환영합니다"         // 한국어
    )

    fun updateLanguage(string: String) {
        _chosenLanguage.postValue(string)
    }
}
