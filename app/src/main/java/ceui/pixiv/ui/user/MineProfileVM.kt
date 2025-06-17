package ceui.pixiv.ui.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MineProfileVM(private val db: AppDatabase) : ViewModel() {

    private val _historyCount = MutableLiveData<Int>()
    val historyCount: LiveData<Int> = _historyCount

    fun calc() {
        viewModelScope.launch(Dispatchers.IO) {
            val illustHistoryCount =
                db.generalDao().getCountByRecordType(RecordType.VIEW_ILLUST_HISTORY)
            val novelHistoryCount =
                db.generalDao().getCountByRecordType(RecordType.VIEW_NOVEL_HISTORY)
            val userHistoryCount =
                db.generalDao().getCountByRecordType(RecordType.VIEW_USER_HISTORY)
            _historyCount.postValue(illustHistoryCount + novelHistoryCount + userHistoryCount)
        }
    }
}