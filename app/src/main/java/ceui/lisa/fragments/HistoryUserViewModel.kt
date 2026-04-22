package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryUserViewModel : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()

    private val _holders = MutableLiveData<List<ListItemHolder>>(emptyList())
    val holders: LiveData<List<ListItemHolder>> = _holders

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val rawItems = mutableListOf<GeneralEntity>()
    private var onDeleteCallback: ((GeneralEntity) -> Unit)? = null

    fun setDeleteCallback(cb: (GeneralEntity) -> Unit) {
        onDeleteCallback = cb
    }

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getByRecordType(RecordType.VIEW_USER_HISTORY, 0, PAGE_SIZE)
            }
            rawItems.clear()
            rawItems.addAll(data)
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
            onDone()
        }
    }

    fun loadMore(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getByRecordType(RecordType.VIEW_USER_HISTORY, rawItems.size, PAGE_SIZE)
            }
            if (data.isNotEmpty()) {
                rawItems.addAll(data)
                _holders.value = buildHolders()
            }
            onDone()
        }
    }

    fun delete(entity: GeneralEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
            }
            rawItems.removeAll { it.id == entity.id }
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
        }
    }

    private fun buildHolders(): List<ListItemHolder> {
        val deleteHandler: (GeneralEntity) -> Unit = { entity ->
            onDeleteCallback?.invoke(entity)
        }
        return rawItems.map { HistoryUserHolder(it, deleteHandler) }
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
