package ceui.pixiv.ui.common

import android.content.res.AssetManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.IllustResponse
import com.google.gson.Gson

class HomeViewModel(private val assets: AssetManager) : ViewModel() {


    private val _illustResponse = MutableLiveData<IllustResponse>()
    val illustResponse: LiveData<IllustResponse> = _illustResponse

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

    fun reset() {
        _currentScale.value = 1F
    }


    private fun loadFromLocal() {
        val jsonString =
            assets.open("landing_bg.json").bufferedReader().use { it.readText() }
        val raw = Gson().fromJson(jsonString, IllustResponse::class.java)
        val list = raw.displayList
        _illustResponse.value = raw.copy(illusts = list.shuffled())
    }

    init {
        loadFromLocal()
    }
}
