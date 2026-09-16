package ceui.lisa.fragments

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.downloadProbeDispatcher
import ceui.lisa.database.hasDownloadRecord
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.communication.StateSource
import ceui.pixiv.download.DownloadRecordStateSource
import ceui.pixiv.utils.fetchFullIllustDetail
import ceui.pixiv.utils.fetchIllustPageDimensions
import ceui.pixiv.utils.hasTrustedCaption
import ceui.pixiv.utils.isFullDetail
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * VM for the "new" illust detail page (FragmentIllust).
 *
 * Exists primarily to move the download-state probe (SAF existence + Room query)
 * off the main thread — on Android 11+ SAF queries per page add up fast, and for
 * multi-P works this was ANR'ing the detail screen on entry (issue #835).
 */
class FragmentIllustViewModel(
    private val illustId: Long,
    downloadStates: StateSource<Long, Boolean>,
) : ViewModel() {

    val downloadState = downloadStates.observe(illustId)

    // ── 每页真实宽高(网页 ajax /ajax/illust/{id}/pages)──
    // 与 ArtworkV3ViewModel 同一套:多 P 时提前拿到每页宽高,让顶部大图下载前就按真 ratio 摆准高度。
    // Fragment 观察 [pageDimensions] 喂给 IllustAdapter.seedPageDimensions;缺 cookie/失败静默降级。
    private val _pageDimensions = MutableLiveData<List<IntArray>>()

    /** 每一 P 的 [width, height],按页序;拉不到则不发射(沿用解码后异步定高,不影响使用)。 */
    val pageDimensions: LiveData<List<IntArray>> = _pageDimensions

    private var pageDimsRequested = false

    private val illustBeanLiveData = ObjectPool.get<Illust>(illustId)
    private val illustBeanObserver = Observer<Illust> { bean -> ensurePageDimensions(bean) }

    init {
        // issue #569: 从「按 Tag 筛选」等精简来源进来时,池里的 bean 缺分页图/原图。后台回 API 拉完整版,
        // 整体覆盖 ObjectPool 后,FragmentIllust 的 illust observer 会带完整数据再次 fire、自动重建图片区。
        // 拉取失败则保留现有(精简)数据 —— GlideUtil / IllustDownload 已加空值兜底,不会崩,降级显示封面。
        viewModelScope.launch {
            val cur = ObjectPool.get<Illust>(illustId).value
            // hasTrustedCaption:列表接口会不定期掐掉部分作品的 caption(#960),caption 为空
            // 且没被 detail 确认过时也回源补拉,落池后 illust observer 自动重渲染简介。
            if (cur == null || !cur.isFullDetail() || !cur.hasTrustedCaption()) {
                fetchFullIllustDetail(illustId)
            }
        }
        // 完整 bean 落地(page_count 可信)时触发一次每页宽高拉取。
        illustBeanLiveData.observeForever(illustBeanObserver)
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
    }

    /** 多 P 首次拿到 bean 时拉一次每页真实宽高(单 P 无需、只拉一次)。缺 cookie/失败静默降级。 */
    private fun ensurePageDimensions(bean: Illust) {
        if (pageDimsRequested || bean.page_count < 2) return
        pageDimsRequested = true
        viewModelScope.launch {
            fetchIllustPageDimensions(illustId)?.let { _pageDimensions.value = it }
        }
    }

    class Factory(private val illustId: Long, context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val states = DownloadRecordStateSource(
                invalidations = { id ->
                    // Only this work's indexed record presence can invalidate a table-driven probe.
                    // Unrelated downloads must not repeat SAF/legacy LIKE scans for the open work.
                    val recordChanges = DownloadRecordStateSource.recordChanges(
                        id, ManagerReactive.doneTableInvalidations,
                    ) { key ->
                        withContext(downloadProbeDispatcher) {
                            AppDatabase.getAppDatabase(appContext).downloadDao()
                                .hasDownloadRecordByIllustIdIndexed(key)
                        }
                    }
                    // Bookmark/view counters cannot affect a saved path. Preserve real metadata
                    // changes (title, author, page URLs, etc.) and the initial/reopened-page probe.
                    val fileMetadata = ObjectPool.get<Illust>(id).asFlow()
                        .map { it.copy(is_bookmarked = null, total_bookmarks = null, total_view = null) }
                        .distinctUntilChanged()
                    combine(
                        recordChanges,
                        fileMetadata,
                    ) { _, _ -> Unit }.conflate()
                },
                probe = { id ->
                    val illust = ObjectPool.get<Illust>(id).value
                    withContext(downloadProbeDispatcher) {
                        // Indexed record lookup first. Downloaded multi-page works never need an
                        // expensive page-by-page SAF scan on entry. Keep legacy backfill support.
                        val hasRecord = AppDatabase.getAppDatabase(appContext)
                            .downloadDao().hasDownloadRecord(id)
                        val hasLocalFile = !hasRecord && illust != null && Common.isIllustDownloaded(illust)
                        Timber.tag(DownloadRecordStateSource.LOG_TAG).d(
                            "probe illustId=%d record=%s localFile=%s fileProbeSkipped=%s",
                            id, hasRecord, hasLocalFile, hasRecord,
                        )
                        hasRecord || hasLocalFile
                    }
                },
            )
            return FragmentIllustViewModel(illustId, states) as T
        }
    }
}
