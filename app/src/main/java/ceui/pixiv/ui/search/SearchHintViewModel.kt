package ceui.pixiv.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.model.ListTrendingtag
import ceui.loxia.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class SearchHintViewModel : ViewModel() {

    private val _hints = MutableLiveData<List<ListTrendingtag.TrendTagsBean>>(emptyList())
    val hints: LiveData<List<ListTrendingtag.TrendTagsBean>> = _hints

    private val _currentKeyword = MutableLiveData("")
    val currentKeyword: LiveData<String> = _currentKeyword

    private val _hintsVisible = MutableLiveData(false)
    val hintsVisible: LiveData<Boolean> = _hintsVisible

    private var debounceJob: Job? = null

    fun onTextChanged(lastWord: String) {
        debounceJob?.cancel()
        // 候选词也是搜索请求；高风险输入不应在用户按下搜索前被自动补全接口发出。
        if (lastWord.isBlank()) {
            clearHints()
            return
        }
        // 热路径直接判断约为亚微秒；极端情况下后台预热还没完成，就把冷解密合并进
        // 本来已有的 debounce 协程，绝不让 TextWatcher 阻塞 UI。
        val blockedWhenWarm = SearchRiskPolicy.takeIf { it.isWarmedUp() }
            ?.shouldWithhold(lastWord)
        if (blockedWhenWarm == true) {
            clearHints()
            return
        }
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            if (blockedWhenWarm == null && withContext(Dispatchers.Default) {
                    SearchRiskPolicy.shouldWithhold(lastWord)
                }
            ) {
                _hints.value = emptyList()
                _currentKeyword.value = ""
                _hintsVisible.value = false
                return@launch
            }
            try {
                val result = Client.appApi.searchAutocomplete(lastWord)
                val list = result.list.orEmpty()
                _hints.value = list
                _currentKeyword.value = lastWord
                _hintsVisible.value = list.isNotEmpty()
            } catch (e: Exception) {
                Timber.e(e, "searchAutocomplete failed for word=$lastWord")
                _hintsVisible.value = false
            }
        }
    }

    fun hideHints() {
        _hintsVisible.value = false
    }

    fun clearHints() {
        debounceJob?.cancel()
        _hints.value = emptyList()
        _currentKeyword.value = ""
        _hintsVisible.value = false
    }

    fun showHintsIfAvailable() {
        if (!_hints.value.isNullOrEmpty()) {
            _hintsVisible.value = true
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
    }
}
