package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * 浏览历史各 tab 的多选态(跨配置存活),键统一用 Long:插画/小说 tab 传 entity.illustID(Long),
 * 用户 tab 传 entity.id(uid)。对齐旧 History*ViewModel 的多选字段,拆成独立 VM 让 feeds 版
 * [FragmentHistoryList] / [FragmentHistoryUserList] 的列表数据(FeedViewModel)与选中态解耦。
 */
class HistorySelectionViewModel : ViewModel() {

    private val _selectionMode = MutableLiveData(false)
    val selectionMode: LiveData<Boolean> = _selectionMode

    private val _selectedIds = MutableLiveData<Set<Long>>(emptySet())
    val selectedIds: LiveData<Set<Long>> = _selectedIds

    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    fun setSelectionMode(enabled: Boolean) {
        if (_selectionMode.value == enabled) return
        _selectionMode.value = enabled
        if (!enabled) setSelected(emptySet())
    }

    fun toggle(id: Long) {
        val current = _selectedIds.value.orEmpty()
        setSelected(if (id in current) current - id else current + id)
    }

    fun setSelected(ids: Set<Long>) {
        _selectedIds.value = ids
        _selectedCount.value = ids.size
    }

    fun clear() = setSelected(emptySet())
}
