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
import ceui.pixiv.ui.task.DownloadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.task.TaskStatus
import ceui.pixiv.download.config.DownloadItems
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
            illustBean = bean
            _isBookmarked.value = bean.isIs_bookmarked
            buildHeaderItems()
            setupDownloadFab(bean)
        }
    }

    // ── download FAB state machine ──
    //
    // Each page of the illust is backed by a pool-resident DownloadTask, the
    // same type 二级详情页 uses — so downloading here warms the Glide cache
    // and gives that page instant display. The MediatorLiveData fuses every
    // page's task status plus a legacy-path fallback into a single state that
    // the fragment renders in one switch.
    private var downloadTasks: List<DownloadTask> = emptyList()
    private var downloadFabInitialized = false
    private val fabRefreshTick = MutableLiveData(0)

    // Result of the SAF / download-DB check. null = not computed yet.
    // The check touches ContentResolver (SAF) and SQLite LIKE scans, so it
    // must never run on the main thread — stale user data can make it take
    // seconds and trigger ANR.
    private var legacyDownloadedCache: Boolean? = null
    private var legacyCheckInFlight = false

    private val _downloadFabState = MediatorLiveData<DownloadFab>().apply {
        value = DownloadFab.Idle
        addSource(fabRefreshTick) { recomputeFab() }
    }
    val downloadFabState: LiveData<DownloadFab> = _downloadFabState

    fun triggerDownload() {
        downloadTasks.forEach { it.start { } }
    }

    /** Re-evaluate state; call after paths outside our observation (legacy
     * IllustDownload → Manager DB) may have changed. */
    fun refreshDownloadFab() {
        legacyDownloadedCache = null
        fabRefreshTick.value = (fabRefreshTick.value ?: 0) + 1
    }

    private fun setupDownloadFab(illust: IllustsBean) {
        if (downloadFabInitialized) return
        val urls = buildDownloadNamedUrls(illust)
        if (urls.isEmpty()) {
            recomputeFab()
            return
        }
        downloadFabInitialized = true
        downloadTasks = urls.map { TaskPool.getDownloadTask(it) }
        downloadTasks.forEach { task ->
            _downloadFabState.addSource(task.status) { recomputeFab() }
        }
        recomputeFab()
    }

    private fun recomputeFab() {
        val tasks = downloadTasks
        if (tasks.any { it.status.value is TaskStatus.Executing }) {
            val avg = tasks.sumOf { t ->
                when (val s = t.status.value) {
                    is TaskStatus.Executing -> s.percentage.coerceIn(0, 100)
                    is TaskStatus.Finished -> 100
                    else -> 0
                }
            } / tasks.size
            _downloadFabState.value = DownloadFab.Downloading(avg.coerceIn(0, 100))
            return
        }
        if (tasks.isNotEmpty() && tasks.all { it.status.value is TaskStatus.Finished }) {
            _downloadFabState.value = DownloadFab.Done
            return
        }
        val cached = legacyDownloadedCache
        if (cached != null) {
            _downloadFabState.value = if (cached) DownloadFab.Done else DownloadFab.Idle
            return
        }
        // Don't downgrade an existing Done while the async check is running.
        if (_downloadFabState.value !is DownloadFab.Done) {
            _downloadFabState.value = DownloadFab.Idle
        }
        triggerLegacyDownloadedCheck()
    }

    private fun triggerLegacyDownloadedCheck() {
        if (legacyCheckInFlight) return
        val bean = illustBean ?: return
        legacyCheckInFlight = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
                    Common.isIllustDownloaded(bean) ||
                            dao.hasDownloadRecordByIllustId(bean.id.toLong())
                } catch (e: Exception) {
                    Timber.e(e, "legacy downloaded check failed")
                    false
                }
            }
            legacyDownloadedCache = result
            legacyCheckInFlight = false
            recomputeFab()
        }
    }

    private fun buildDownloadNamedUrls(illust: IllustsBean): List<NamedUrl> {
        return if (illust.page_count <= 1) {
            val url = illust.meta_single_page?.original_image_url ?: illust.image_urls?.original
            listOfNotNull(url?.let { NamedUrl(DownloadItems.illustFileName(illust, 0), it) })
        } else {
            illust.meta_pages.orEmpty().mapIndexedNotNull { i, page ->
                page.image_urls?.original?.let { NamedUrl(DownloadItems.illustFileName(illust, i), it) }
            }
        }
    }

    init {
        illustBeanLiveData.observeForever(illustBeanObserver)
        loadData()
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.removeCallbacks(enableLoadMoreRunnable)
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
        if (illustBean != null) return
        viewModelScope.launch {
            try {
                ObjectPool.get<Illust>(illustId).value
                    ?: withContext(Dispatchers.IO) {
                        val ctx = Shaft.getContext()
                        AppDatabase.getAppDatabase(ctx).generalDao()
                            .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                            ?.typedObject<Illust>()?.also { ObjectPool.update(it) }
                    }
                    ?: Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
            } catch (e: Exception) {
                Timber.e(e)
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
