package ceui.pixiv.ui.works

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    private val _event = MutableLiveData<Int>()
    val event: LiveData<Int> get() = _event

    fun triggerEvent(data: Int) {
        _event.value = data
    }

    fun markAsTriggered() {
        _event.value = -1
    }
}
