package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.loxia.ObjectPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryListViewModel(private val historyType: Int) : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()

    private val _items = MutableLiveData<List<IllustHistoryEntity>>(emptyList())
    val items: LiveData<List<IllustHistoryEntity>> = _items

    private val _illusts = MutableLiveData<List<IllustsBean>>(emptyList())
    val illusts: LiveData<List<IllustsBean>> = _illusts

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val allItems = mutableListOf<IllustHistoryEntity>()
    private val allIllusts = mutableListOf<IllustsBean>()

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getViewHistoryByType(historyType, PAGE_SIZE, 0)
            }
            allItems.clear()
            allItems.addAll(data)
            rebuildIllusts()
            _items.value = allItems.toList()
            _illusts.value = allIllusts.toList()
            _isEmpty.value = allItems.isEmpty()
            onDone()
        }
    }

    fun loadMore(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getViewHistoryByType(historyType, PAGE_SIZE, allItems.size)
            }
            if (data.isNotEmpty()) {
                allItems.addAll(data)
                appendIllusts(data)
                _items.value = allItems.toList()
                _illusts.value = allIllusts.toList()
            }
            onDone()
        }
    }

    fun delete(entity: IllustHistoryEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.delete(entity) }
            val pos = allItems.indexOfFirst { it.illustID == entity.illustID && it.type == entity.type }
            if (pos >= 0) {
                allItems.removeAt(pos)
                if (entity.type == 0) allIllusts.removeAll { it.id == entity.illustID }
            }
            _items.value = allItems.toList()
            _illusts.value = allIllusts.toList()
            _isEmpty.value = allItems.isEmpty()
        }
    }

    private fun rebuildIllusts() {
        allIllusts.clear()
        if (historyType != 0) return
        allItems.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); allIllusts.add(it) }
    }

    private fun appendIllusts(newItems: List<IllustHistoryEntity>) {
        if (historyType != 0) return
        newItems.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); allIllusts.add(it) }
    }

    companion object {
        private const val PAGE_SIZE = 30

        fun factory(type: Int) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryListViewModel(type) as T
        }
    }
}
