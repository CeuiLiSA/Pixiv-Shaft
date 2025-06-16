package ceui.pixiv.ui.rank

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Event

class RankDayViewModel : ViewModel() {
    private val _rankDay = MutableLiveData<String?>(null)

    val rankDay: LiveData<String?> = _rankDay

    private val _refreshEvent = MutableLiveData<Event<Long>>()
    val refreshEvent: LiveData<Event<Long>> = _refreshEvent

    fun applyRankDay(rankDay: String?) {
        if (rankDay == null && _rankDay.value == null) {
            return
        }

        if (rankDay == _rankDay.value) {
            return
        }

        _rankDay.value = rankDay
        _refreshEvent.value = Event(System.currentTimeMillis())
    }
}