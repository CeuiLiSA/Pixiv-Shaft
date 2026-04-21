package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryUserViewModel : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()

    private val _items = MutableLiveData<List<GeneralEntity>>(emptyList())
    val items: LiveData<List<GeneralEntity>> = _items

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val allItems = mutableListOf<GeneralEntity>()

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getByRecordType(RecordType.VIEW_USER_HISTORY, 0, PAGE_SIZE)
            }
            allItems.clear()
            allItems.addAll(data)
            _items.value = allItems.toList()
            _isEmpty.value = allItems.isEmpty()
            onDone()
        }
    }

    fun loadMore(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getByRecordType(RecordType.VIEW_USER_HISTORY, allItems.size, PAGE_SIZE)
            }
            if (data.isNotEmpty()) {
                allItems.addAll(data)
                _items.value = allItems.toList()
            }
            onDone()
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
