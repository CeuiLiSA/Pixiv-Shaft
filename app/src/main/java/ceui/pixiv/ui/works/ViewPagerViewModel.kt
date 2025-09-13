package ceui.pixiv.ui.works

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Event

class ViewPagerViewModel : ViewModel() {


    private val _maps = hashMapOf<String, MutableLiveData<Event<Int>>>()

    fun getDownloadEvent(name: String): LiveData<Event<Int>> {
        return _maps.getOrPut(name) {
            MutableLiveData<Event<Int>>()
        }
    }

    fun triggerDownloadEvent(index: Int, name: String) {
        val liveData = _maps.getOrPut(name) {
            MutableLiveData<Event<Int>>()
        }
        liveData.postValue(Event(index))
    }

    private val _cropEvent = MutableLiveData<Event<Int>>()
    val cropEvent: LiveData<Event<Int>> = _cropEvent

    fun triggerCropEvent(index: Int) {
        _cropEvent.postValue(Event(index))
    }
}
