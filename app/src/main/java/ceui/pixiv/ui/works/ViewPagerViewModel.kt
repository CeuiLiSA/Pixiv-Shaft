package ceui.pixiv.ui.works

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Event

class ViewPagerViewModel : ViewModel() {

    private val _downloadEvent = MutableLiveData<Event<Int>>()
    val downloadEvent: LiveData<Event<Int>> = _downloadEvent

    fun triggerDownloadEvent(index: Int) {
        _downloadEvent.postValue(Event(index))
    }

    private val _cropEvent = MutableLiveData<Event<Int>>()
    val cropEvent: LiveData<Event<Int>> = _cropEvent

    fun triggerCropEvent(index: Int) {
        _cropEvent.postValue(Event(index))
    }
}
