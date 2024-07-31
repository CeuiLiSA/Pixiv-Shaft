package ceui.pixiv.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.HomeIllustResponse
import ceui.loxia.RefreshState
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    val obj = MutableLiveData<HomeIllustResponse>()

    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("api-cache")
    }
    private val gson = Gson()

    init {
        refresh()
    }


    fun refresh() {
        viewModelScope.launch {
            try {
                val key = "home-data"
                _refreshState.value = RefreshState.LOADING()
                val json = prefStore.getString(key, "")
                if (json?.isNotEmpty() == true) {
                    obj.value = gson.fromJson(json, HomeIllustResponse::class.java)
                } else {
                    val apiData = Client.appApi.getHomeData()
                    prefStore.putString(key, gson.toJson(apiData))
                    obj.value = apiData
                }
                _refreshState.value = RefreshState.LOADED()
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }

    private fun loadMore() {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING()

                _refreshState.value = RefreshState.LOADED()
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                ex.printStackTrace()
            }
        }
    }

}