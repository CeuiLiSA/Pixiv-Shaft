package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModal : ViewModel() {

    private val _grayDisplay = MutableLiveData(false)
    val grayDisplay: LiveData<Boolean> = _grayDisplay

    fun toggleGrayModeImpl() {
        _grayDisplay.value = _grayDisplay.value?.not() == true
    }
}