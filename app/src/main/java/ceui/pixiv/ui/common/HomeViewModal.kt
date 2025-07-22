package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import timber.log.Timber

class HomeViewModel(private val tag: String) : ViewModel() {

    init {
        Timber.d("HomeViewModel($tag) created")
    }

    private val _grayDisplay = MutableLiveData(false)
    val grayDisplay: LiveData<Boolean> = _grayDisplay

    private val navStack = mutableListOf<Int>()

    private val _currentScale = MutableLiveData(1f)
    val currentScale: LiveData<Float> = _currentScale

    fun toggleGrayModeImpl() {
        _grayDisplay.value = _grayDisplay.value?.not() == true
    }

    fun onDestinationChanged(destId: Int) {
        val lastId = navStack.lastOrNull()

        when {
            lastId == null -> {
                // 初始化导航堆栈
                navStack.add(destId)
            }

            lastId == destId -> {
                // 重复导航，无需处理
            }

            navStack.contains(destId) -> {
                // Pop 操作
                while (navStack.isNotEmpty() && navStack.last() != destId) {
                    navStack.removeAt(navStack.size - 1)
                }
                _currentScale.value = (_currentScale.value ?: 1f) / 1.1f
            }

            else -> {
                // Push 操作
                navStack.add(destId)
                _currentScale.value = (_currentScale.value ?: 1f) * 1.1f
            }
        }
    }
}
