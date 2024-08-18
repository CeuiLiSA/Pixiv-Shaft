package ceui.pixiv.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import kotlinx.coroutines.launch

fun <T> Fragment.pixivValueViewModel(
    loader: suspend () -> T,
): Lazy<ValueViewModel<T>> {
    return this.viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ValueViewModel(loader) as T
            }
        }
    }
}


class ValueViewModel<T>(
    private val loader: suspend () -> T,
) : ViewModel() {

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    private val _result = MutableLiveData<T>()
    val result: LiveData<T> = _result

    init {
        refresh(RefreshHint.InitialLoad)
    }

    fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
               val response = loader()
                _result.value = response
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true,
                    hasNext = false
                )
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }
}