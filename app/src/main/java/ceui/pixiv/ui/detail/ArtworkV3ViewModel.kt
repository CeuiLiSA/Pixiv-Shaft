package ceui.pixiv.ui.detail

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.lisa.download.IllustDownload
import ceui.pixiv.db.RecordType
import com.google.gson.Gson
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ArtworkV3ViewModel(
    private val illustId: Long
) : ViewModel() {

    // ── internal state ──
    private var illustBean: IllustsBean? = null
    private val gson = Gson()
    private var commentsLoadTriggered = false
    private var authorWorksLoadTriggered = false
    private var relatedLoadTriggered = false

    // ── output: header sections ──
    private val _headerItems = MutableLiveData<List<ArtworkDetailItem>>()
    val headerItems: LiveData<List<ArtworkDetailItem>> = _headerItems

    // ── lazy-loaded section data (observed directly by ViewHolders) ──
    private val _commentsData = MutableLiveData<List<Comment>?>(null)
    val commentsData: LiveData<List<Comment>?> = _commentsData

    private val _authorWorksData = MutableLiveData<List<IllustsBean>?>(null)
    val authorWorksData: LiveData<List<IllustsBean>?> = _authorWorksData

    private val _relatedState = MutableLiveData<Boolean?>(null)
    val relatedState: LiveData<Boolean?> = _relatedState

    // ── output: related illusts (for IAdapter) ──
    private val relatedList = mutableListOf<IllustsBean>()
    private val _relatedIllusts = MutableLiveData<List<IllustsBean>>()
    val relatedIllusts: LiveData<List<IllustsBean>> = _relatedIllusts

    private val _isBookmarked = MutableLiveData<Boolean>()
    val isBookmarked: LiveData<Boolean> = _isBookmarked

    var relatedNextUrl: String? = null
        private set
    private val _isLoadingRelated = MutableLiveData(false)
    val isLoadingRelated: LiveData<Boolean> = _isLoadingRelated
    private var isLoadingMore = false
    val hasMoreRelated: Boolean get() = !relatedNextUrl.isNullOrEmpty() && !isLoadingMore

    // ── ObjectPool observers ──
    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)

    // Coalesce multiple triggers fired within the same main-thread turn
    // into a single header rebuild.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { doBuildHeaderItems() }
    private val enableLoadMoreRunnable = Runnable { isLoadingMore = false }

    private val illustBeanObserver = Observer<IllustsBean> { bean ->
        if (bean != null) {
            val mpInfo = try {
                if (bean.meta_pages == null) "null" else "size=${bean.meta_pages.size}"
            } catch (e: Throwable) { "throws ${e.javaClass.simpleName}" }
            Timber.tag("V3MultiP").d(
                "[ViewModel.illustBeanObserver] FIRE illustId=$illustId, " +
                    "page_count=${bean.page_count}, w=${bean.width}, h=${bean.height}, " +
                    "meta_pages=$mpInfo, prevIllustBeanWasNull=${illustBean == null}"
            )
            illustBean = bean
            _isBookmarked.value = bean.isIs_bookmarked
            buildHeaderItems()
            setupDownloadFab(bean)
        } else {
            Timber.tag("V3MultiP").w("[ViewModel.illustBeanObserver] FIRE illustId=$illustId, bean=NULL")
        }
    }

    // ── download FAB state machine ──
    //
    // 通过 Manager 队列串行下载，不再自行创建 DownloadTask。
    // FAB 状态仅依赖 Manager 是否正在下载当前作品 + DB 是否已有下载记录。
    private var downloadFabInitialized = false
    private val fabRefreshTick = MutableLiveData(0)

    private var downloadedCache: Boolean? = null
    private var downloadCheckInFlight = false

    private val recomputeFabRunnable = Runnable { recomputeFab() }

    private val _downloadFabState = MediatorLiveData<DownloadFab>().apply {
        value = DownloadFab.Idle
        addSource(fabRefreshTick) { recomputeFab() }
    }
    val downloadFabState: LiveData<DownloadFab> = _downloadFabState

    private var progressPolling = false

    fun triggerDownload() {
        val illust = illustBean ?: return
        IllustDownload.downloadIllustAllPages(illust)
        _downloadFabState.value = DownloadFab.Downloading(0)
        startProgressPolling(illust.page_count)
    }

    /** 轮询 Manager 队列中当前 illust 的下载进度 */
    private fun startProgressPolling(pageCount: Int) {
        if (progressPolling) return
        progressPolling = true
        viewModelScope.launch {
            while (progressPolling) {
                kotlinx.coroutines.delay(500)
                val manager = ceui.lisa.core.Manager.get()
                val items = manager.content
                val illustItems = items.filter {
                    it.illust?.id == illustId.toInt()
                }
                if (illustItems.isEmpty()) {
                    // 队列中没有了 → 全部完成
                    progressPolling = false
                    refreshDownloadFab()
                    break
                }
                // 已完成的页数 = 总页数 - 队列中剩余页数
                val completedPages = pageCount - illustItems.size
                val currentPageProgress = illustItems.firstOrNull()?.let {
                    if (it.state == ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING) it.nonius else 0
                } ?: 0
                // 总进度 = (已完成页 * 100 + 当前页进度) / 总页数
                val totalPercent = if (pageCount > 0) {
                    ((completedPages * 100 + currentPageProgress) / pageCount).coerceIn(0, 100)
                } else 0
                _downloadFabState.value = DownloadFab.Downloading(totalPercent)
            }
        }
    }

    fun refreshDownloadFab() {
        progressPolling = false
        downloadedCache = null
        fabRefreshTick.value = (fabRefreshTick.value ?: 0) + 1
    }

    private fun setupDownloadFab(illust: IllustsBean) {
        if (downloadFabInitialized) return
        downloadFabInitialized = true
        // 若 Manager 正在下载本作品，启动进度轮询
        val items = ceui.lisa.core.Manager.get().content
        val hasItems = items.any { it.illust?.id == illustId.toInt() }
        if (hasItems) {
            _downloadFabState.value = DownloadFab.Downloading(0)
            startProgressPolling(illust.page_count)
        } else {
            recomputeFab()
        }
    }

    private fun recomputeFab() {
        val cached = downloadedCache
        if (cached != null) {
            _downloadFabState.value = if (cached) DownloadFab.Done else DownloadFab.Idle
            return
        }
        if (_downloadFabState.value !is DownloadFab.Done) {
            _downloadFabState.value = DownloadFab.Idle
        }
        triggerDownloadedCheck()
    }

    private fun triggerDownloadedCheck() {
        if (downloadCheckInFlight) return
        val bean = illustBean ?: return
        downloadCheckInFlight = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
                    Common.isIllustDownloaded(bean) ||
                            dao.hasDownloadRecordByIllustId(bean.id.toLong())
                } catch (e: Exception) {
                    Timber.e(e, "downloaded check failed")
                    false
                }
            }
            downloadedCache = result
            downloadCheckInFlight = false
            recomputeFab()
        }
    }

    init {
        // Note: only read the IllustsBean LiveData (already created above via the
        // member val). Do NOT touch ObjectPool.get<Illust>(...) here — it would
        // create a fresh empty entry as a side effect, which loadData() may not
        // have done in the short-circuit branch.
        Timber.tag("V3MultiP").d(
            "[ViewModel.init] illustId=$illustId, " +
                "IllustsBeanPoolHasValue=${illustBeanLiveData.value != null}"
        )
        illustBeanLiveData.observeForever(illustBeanObserver)
        loadData()
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.removeCallbacks(enableLoadMoreRunnable)
        mainHandler.removeCallbacks(recomputeFabRunnable)
    }

    // ── build header item list (everything except individual related cards) ──
    private fun buildHeaderItems() {
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.post(rebuildRunnable)
    }

    private fun doBuildHeaderItems() {
        val illust = illustBean ?: return
        val list = mutableListOf<ArtworkDetailItem>()

        list.add(ArtworkDetailItem.Hero(illust))

        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            list.add(ArtworkDetailItem.Series(illust))
        }

        list.add(ArtworkDetailItem.Artist(illust))

        if (!TextUtils.isEmpty(illust.caption)) {
            list.add(ArtworkDetailItem.Desc(illust.caption))
        }

        list.add(ArtworkDetailItem.Tags(illust))
        list.add(ArtworkDetailItem.Stats(illust))
        list.add(ArtworkDetailItem.DetailPanel(illust))

        list.add(ArtworkDetailItem.Comments(_commentsData, illust.id, illust.title ?: ""))
        list.add(
            ArtworkDetailItem.AuthorWorks(
                _authorWorksData,
                illust.user?.name ?: "",
                illust.user?.id ?: 0
            )
        )
        list.add(ArtworkDetailItem.RelatedHeader(_relatedState, illust.id, illust.title ?: ""))

        _headerItems.value = list
    }

    // ── data loading (deep-link fallback) ──
    private fun loadData() {
        // IllustsBean is normally already in ObjectPool from the list page.
        // Only fetch when entering via deep link / history where the pool is empty.
        if (illustBean != null) {
            Timber.tag("V3MultiP").d("[ViewModel.loadData] short-circuit: IllustsBean already present (illustId=$illustId)")
            return
        }
        Timber.tag("V3MultiP").d(
            "[ViewModel.loadData] IllustsBean is NULL, falling through to Illust pool / DB / API path. " +
                "WARNING: this path only updates Illust(modern) pool, NOT IllustsBean(legacy) — " +
                "Fragment observes IllustsBean and may never get adapter-created."
        )
        viewModelScope.launch {
            try {
                val fromIllustPool = ObjectPool.get<Illust>(illustId).value
                Timber.tag("V3MultiP").d("[ViewModel.loadData] Illust(modern) pool value=${fromIllustPool != null}")
                fromIllustPool
                    ?: withContext(Dispatchers.IO) {
                        val ctx = Shaft.getContext()
                        AppDatabase.getAppDatabase(ctx).generalDao()
                            .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                            ?.typedObject<Illust>()?.also {
                                Timber.tag("V3MultiP").d("[ViewModel.loadData] hit DB history, updating Illust(modern) pool")
                                ObjectPool.update(it)
                            }
                    }
                    ?: Client.appApi.getIllust(illustId).illust?.also {
                        Timber.tag("V3MultiP").d(
                            "[ViewModel.loadData] fetched via API, page_count=${it.page_count}, " +
                                "meta_pages=${it.meta_pages?.size ?: -1}; updating Illust(modern) pool"
                        )
                        ObjectPool.update(it)
                    }
            } catch (e: Exception) {
                Timber.tag("V3MultiP").e(e, "[ViewModel.loadData] EXCEPTION")
            }
        }
    }

    fun loadComments() {
        if (commentsLoadTriggered) return
        commentsLoadTriggered = true
        viewModelScope.launch {
            _commentsData.value = withContext(Dispatchers.IO) {
                runCatching {
                    Client.appApi.getIllustComments(illustId).comments.take(3)
                }
                    .getOrElse { Timber.e(it); emptyList() }
            }
        }
    }

    fun loadAuthorWorks() {
        if (authorWorksLoadTriggered) return
        authorWorksLoadTriggered = true
        val userId = illustBean?.user?.id ?: return
        viewModelScope.launch {
            _authorWorksData.value = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val resp = ceui.lisa.http.Retro.getAppApi()
                            .getUserSubmitIllust(userId, "illust")
                            .awaitFirst()
                        resp.list?.filter { it.id != illustId.toInt() }?.take(10) ?: emptyList()
                    }.getOrElse { Timber.e(it); emptyList() }
                }
            } catch (e: Exception) {
                Timber.e(e); emptyList()
            }
        }
    }

    fun loadRelated() {
        if (relatedLoadTriggered) return
        relatedLoadTriggered = true
        viewModelScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    runCatching {
                        val body = Client.appApi.generalGet(
                            "https://app-api.pixiv.net/v2/illust/related?illust_id=$illustId"
                        )
                        gson.fromJson(body.string(), ListIllust::class.java)
                    }.getOrElse { Timber.e(it); null }
                }
                resp?.let { r ->
                    relatedList.clear()
                    relatedList.addAll(r.list ?: emptyList())
                    relatedNextUrl = r.next_url
                    _relatedIllusts.value = relatedList.toList()
                }
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                _relatedState.value = relatedList.isNotEmpty()
                // Prevent scroll inertia from immediately triggering loadMoreRelated
                isLoadingMore = true
                mainHandler.postDelayed(enableLoadMoreRunnable, 300)
            }
        }
    }

    fun loadMoreRelated() {
        val url = relatedNextUrl
        if (url.isNullOrEmpty() || isLoadingMore) return
        isLoadingMore = true
        _isLoadingRelated.value = true

        viewModelScope.launch(Dispatchers.Main) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    val body = Client.appApi.generalGet(url)
                    gson.fromJson(body.string(), ListIllust::class.java)
                }
                relatedNextUrl = resp.next_url
                resp.list?.let { newItems ->
                    relatedList.addAll(newItems)
                    _relatedIllusts.value = relatedList.toList()
                }
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                isLoadingMore = false
                _isLoadingRelated.value = false
            }
        }
    }
}

/**
 * Bridge Rx2 Observable to a suspend function without pulling in kotlinx-coroutines-rx2.
 * Runs the subscription on Schedulers.io so the calling coroutine thread is freed while waiting.
 */
sealed interface DownloadFab {
    data object Idle : DownloadFab
    data class Downloading(val percent: Int) : DownloadFab
    data object Done : DownloadFab
}

private suspend fun <T : Any> Observable<T>.awaitFirst(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) }
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
