package ceui.pixiv.ui.home

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewPagerViewModel : ViewModel() {

    val selectedTabIndex = MutableLiveData(0)

    val composeButtonState = MutableLiveData(ComposeButtonState.CLOSED)

    fun toggleComposeButton() {
        if (composeButtonState.value == ComposeButtonState.CLOSED) {
            composeButtonState.value = ComposeButtonState.OPEN
        } else {
            composeButtonState.value = ComposeButtonState.CLOSED
        }
    }

    object ComposeButtonState {
        const val CLOSED = 0
        const val OPEN = 1
    }
}
