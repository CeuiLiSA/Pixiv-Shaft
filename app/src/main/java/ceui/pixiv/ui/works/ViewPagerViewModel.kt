package ceui.pixiv.ui.works

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.utils.Common
import ceui.loxia.Event

class ViewPagerViewModel : ViewModel() {

    private val _downloadEvent = MutableLiveData<Event<Int>>()

    val downloadEvent: LiveData<Event<Int>> = _downloadEvent

    fun triggerDownloadEvent(index: Int) {
        _downloadEvent.postValue(Event(index))
    }
}
