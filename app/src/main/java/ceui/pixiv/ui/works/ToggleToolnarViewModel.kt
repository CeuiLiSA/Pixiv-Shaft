package ceui.pixiv.ui.works

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

open class ToggleToolnarViewModel : ViewModel() {

    val isFullscreenMode = MutableLiveData(false)

    fun toggleFullscreen() {
        val current = isFullscreenMode.value ?: false
        isFullscreenMode.value = !current
    }
}