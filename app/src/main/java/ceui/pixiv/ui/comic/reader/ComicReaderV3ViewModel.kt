package ceui.pixiv.ui.comic.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import kotlinx.coroutines.launch
import timber.log.Timber

class ComicReaderV3ViewModel(val illustId: Long) : ViewModel() {

    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        data class Loaded(val illust: IllustsBean, val pages: List<ComicPage>) : LoadState()
        data class Error(val message: String) : LoadState()
    }

    data class ComicPage(
        val index: Int,
        val previewUrl: String,
        val originalUrl: String,
    )

    private val _loadState = MutableLiveData<LoadState>(LoadState.Idle)
    val loadState: LiveData<LoadState> = _loadState

    private val _currentPage = MutableLiveData(0)
    val currentPage: LiveData<Int> = _currentPage

    fun load() {
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading
        val cached = ObjectPool.getIllust(illustId).value
        if (cached != null) {
            applyIllust(cached)
            return
        }
        viewModelScope.launch {
            runCatching { Client.appApi.getIllust(illustId).illust }
                .onSuccess { modern ->
                    if (modern == null) {
                        _loadState.postValue(LoadState.Error("作品不存在"))
                        return@onSuccess
                    }
                    val bean = Shaft.sGson.let { g -> g.fromJson(g.toJson(modern), IllustsBean::class.java) }
                    ObjectPool.updateIllust(bean)
                    applyIllust(bean)
                }
                .onFailure { e ->
                    Timber.tag("ComicReaderV3").e(e, "load failed for illust=$illustId")
                    _loadState.postValue(LoadState.Error(e.message ?: "加载失败"))
                }
        }
    }

    private fun applyIllust(illust: IllustsBean) {
        val total = (illust.page_count.takeIf { it > 0 } ?: 1)
        val pages = (0 until total).map { i ->
            ComicPage(
                index = i,
                previewUrl = IllustDownload.getUrl(illust, i, Params.IMAGE_RESOLUTION_LARGE),
                originalUrl = IllustDownload.getUrl(illust, i, Params.IMAGE_RESOLUTION_ORIGINAL),
            )
        }
        _loadState.postValue(LoadState.Loaded(illust, pages))
        val resume = ComicReaderProgressStore.lastPage(illustId).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        _currentPage.postValue(resume)
    }

    fun onPageChanged(index: Int) {
        if (_currentPage.value == index) return
        _currentPage.value = index
        val total = (_loadState.value as? LoadState.Loaded)?.pages?.size ?: return
        ComicReaderProgressStore.savePage(illustId, index, total)
    }

    fun urlForPage(page: ComicPage): String =
        if (ComicReaderSettings.loadOriginal) page.originalUrl else page.previewUrl

    companion object {
        fun factory(illustId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ComicReaderV3ViewModel(illustId) as T
        }
    }
}
