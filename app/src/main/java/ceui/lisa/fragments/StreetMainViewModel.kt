package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.StreetContent
import ceui.loxia.StreetNextParams
import ceui.loxia.StreetRequest
import ceui.loxia.StreetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreetMainViewModel : ViewModel() {

    private val _items = MutableLiveData<List<StreetContent>>(emptyList())
    val items: LiveData<List<StreetContent>> = _items

    private val _loadState = MutableLiveData<LoadState>(LoadState.Idle)
    val loadState: LiveData<LoadState> = _loadState

    private var nextParams: StreetNextParams? = null
    val hasMore: Boolean get() = nextParams != null

    // 累积已加载的各类 ID，下次请求带上
    private val loadedIllustIds = mutableListOf<String>()
    private val loadedMangaIds = mutableListOf<String>()
    private val loadedNovelIds = mutableListOf<String>()
    private val loadedCollectionIds = mutableListOf<String>()

    fun refresh() = load(refresh = true)
    fun loadMore() = load(refresh = false)

    private fun load(refresh: Boolean) {
        if (_loadState.value == LoadState.Loading) return
        _loadState.value = LoadState.Loading

        viewModelScope.launch {
            try {
                val request = buildRequest(refresh)
                val response = callApi(request, retried = false)

                val contents = response.body?.contents?.filter {
                    it.kind == "illust" || it.kind == "manga" || it.kind == "novel" || it.kind == "collection"
                } ?: emptyList()
                nextParams = response.body?.nextParams

                // 累积 ID
                if (refresh) {
                    loadedIllustIds.clear()
                    loadedMangaIds.clear()
                    loadedNovelIds.clear()
                    loadedCollectionIds.clear()
                }
                for (c in response.body?.contents.orEmpty()) {
                    val tid = c.thumbnails?.firstOrNull()?.id ?: continue
                    when (c.kind) {
                        "illust" -> loadedIllustIds.add(tid)
                        "manga" -> loadedMangaIds.add(tid)
                        "novel" -> loadedNovelIds.add(tid)
                        "collection" -> loadedCollectionIds.add(tid)
                    }
                }

                val current = if (refresh) emptyList() else (_items.value ?: emptyList())
                _items.value = current + contents
                _loadState.value = if (refresh) {
                    LoadState.Refreshed
                } else {
                    LoadState.LoadedMore(current.size, contents.size)
                }
            } catch (e: Exception) {
                _loadState.value = LoadState.Error(e.message ?: "加载失败")
            }
        }
    }

    private fun buildRequest(refresh: Boolean): StreetRequest {
        return if (refresh) {
            StreetRequest()
        } else {
            StreetRequest(
                vhi = loadedIllustIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhm = loadedMangaIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhn = loadedNovelIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhc = loadedCollectionIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            )
        }
    }

    private suspend fun callApi(request: StreetRequest, retried: Boolean): StreetResponse {
        val csrf = CsrfTokenProvider.get()
            ?: throw RuntimeException("CSRF token 未就绪，请重试")

        val response = withContext(Dispatchers.IO) {
            Client.webApi.getStreetMain(csrf, request)
        }

        if (response.error == true && !retried) {
            // token 可能过期，清除缓存后重试一次
            CsrfTokenProvider.clear()
            return callApi(request, retried = true)
        }
        if (response.error == true) {
            throw RuntimeException(response.message ?: "请求失败")
        }
        return response
    }

    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        object Refreshed : LoadState()
        data class LoadedMore(val insertStart: Int, val insertCount: Int) : LoadState()
        data class Error(val message: String) : LoadState()
    }
}
