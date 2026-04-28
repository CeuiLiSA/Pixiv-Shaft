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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel：Comic Reader 的 SSOT（单一真相源）。
 *
 * 持有所有跨配置变更需要存活的状态：
 * - 加载状态 / 当前页 / 页面列表（[loadState] / [currentPage]）
 * - 会话计时器（[ComicStatsTracker]） —— 旋转 Fragment 不会重置计时
 * - 主动预取器（[ComicPagePrefetcher]） —— 指纹去重 across rotation
 *
 * 业务操作（加书签 / 跳系列）经由 UseCase 调度，Fragment 只发 intent。
 * 一次性 UI 效果（Toast、navigate）走 [events] Flow，避免 LiveData 黏性事件 replay 问题。
 */
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

    /** 一次性 UI 效果，每次 collect 仅消费新事件，不 replay。 */
    sealed class UiEvent {
        data class Toast(val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
        data class NavigateToReader(val illustId: Long) : UiEvent()
        object DismissAndFinish : UiEvent()
    }

    private val _loadState = MutableLiveData<LoadState>(LoadState.Idle)
    val loadState: LiveData<LoadState> = _loadState

    private val _currentPage = MutableLiveData(0)
    val currentPage: LiveData<Int> = _currentPage

    private val _events = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val statsTracker = ComicStatsTracker(illustId, ComicReaderGraph.statsRepository)
    private val prefetcher = ComicPagePrefetcher()
    private val addBookmark = ComicReaderGraph.addBookmarkUseCase
    private val flushSession = ComicReaderGraph.flushSessionStatsUseCase
    private val jumpSeries = ComicReaderGraph.jumpSeriesUseCase

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
        val total = illust.page_count.takeIf { it > 0 } ?: 1
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
        prefetcher.reset()
        prefetcher.prefetchAround(pages, resume)
    }

    // ---- Intent API（Fragment 只调这些方法） -------------------------------

    fun onPageChanged(index: Int) {
        if (_currentPage.value == index) return
        val previous = _currentPage.value ?: 0
        _currentPage.value = index
        val total = (_loadState.value as? LoadState.Loaded)?.pages?.size ?: return
        ComicReaderProgressStore.savePage(illustId, index, total)
        if (previous != index) statsTracker.recordFlip()
        (_loadState.value as? LoadState.Loaded)?.pages?.let { prefetcher.prefetchAround(it, index) }
    }

    /** 用户主动 step（左/右点击区 / 音量键）。返回是否成功翻页（用于让 UI 决定边界反馈）。 */
    fun stepPage(forward: Boolean): Boolean {
        val total = (_loadState.value as? LoadState.Loaded)?.pages?.size ?: return false
        val cur = _currentPage.value ?: 0
        val target = if (forward) cur + 1 else cur - 1
        if (target !in 0 until total) return false
        _currentPage.value = target
        return true
    }

    /** session 起始：onResume 调一次。计时由 ViewModel 持有，旋转 Fragment 不重置。 */
    fun onSessionStart() = statsTracker.start()

    /** session 落库：onPause 调一次。Use case 内部走应用级 IO scope。 */
    fun onSessionFlush() {
        val total = (_loadState.value as? LoadState.Loaded)?.pages?.size ?: 0
        statsTracker.flush(_currentPage.value ?: 0, total)
    }

    /** 设置 Image 类变更时（fitMode / loadOriginal）触发预取重算。 */
    fun onImageSettingsChanged() {
        val pages = (_loadState.value as? LoadState.Loaded)?.pages ?: return
        prefetcher.reset()
        prefetcher.prefetchAround(pages, _currentPage.value ?: 0)
    }

    /** 加书签 intent：成功 → events 推送 Toast；失败 → 静默或 toast。 */
    fun addBookmarkAt(pageIndex: Int) {
        val state = _loadState.value as? LoadState.Loaded ?: return
        when (val r = addBookmark.invoke(state.illust, state.pages, pageIndex)) {
            is AddComicBookmarkUseCase.Result.Added ->
                _events.tryEmit(
                    UiEvent.Toast(
                        ceui.lisa.R.string.comic_reader_bookmarks_added,
                        listOf(r.pageIndex + 1),
                    )
                )
            AddComicBookmarkUseCase.Result.InvalidPage -> Unit
        }
    }

    /** 系列上下篇 intent：UseCase 返回 Outcome → 转 UiEvent。 */
    fun jumpSeriesNeighbor(forward: Boolean) {
        val state = _loadState.value as? LoadState.Loaded ?: return
        _events.tryEmit(UiEvent.Toast(ceui.lisa.R.string.comic_reader_series_loading))
        jumpSeries.invoke(viewModelScope, state.illust, forward) { outcome ->
            when (outcome) {
                JumpComicSeriesUseCase.Outcome.NoSeries ->
                    _events.tryEmit(UiEvent.Toast(ceui.lisa.R.string.comic_reader_no_series))
                JumpComicSeriesUseCase.Outcome.AtFirst ->
                    _events.tryEmit(UiEvent.Toast(ceui.lisa.R.string.comic_reader_series_first))
                JumpComicSeriesUseCase.Outcome.AtLast ->
                    _events.tryEmit(UiEvent.Toast(ceui.lisa.R.string.comic_reader_series_last))
                is JumpComicSeriesUseCase.Outcome.Found ->
                    _events.tryEmit(UiEvent.NavigateToReader(outcome.illustId))
            }
        }
    }

    fun urlForPage(page: ComicPage): String =
        ComicPageUrlResolver.resolve(page, ComicReaderSettings.loadOriginal)

    companion object {
        fun factory(illustId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ComicReaderV3ViewModel(illustId) as T
        }
    }
}
