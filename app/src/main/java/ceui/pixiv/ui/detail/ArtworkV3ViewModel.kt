package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.downloadProbeDispatcher
import ceui.lisa.database.hasDownloadRecord
import ceui.lisa.download.IllustDownload
import ceui.loxia.Illust
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import ceui.loxia.fetchFullIllustDetail
import ceui.loxia.fetchIllustPageDimensions
import ceui.loxia.hasTrustedCaption
import ceui.loxia.isFullDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ArtworkV3 详情页的 chrome VM(feeds 版精简后只剩两件事):
 * - 悬浮下载胶囊状态机(排队 / 轮询进度 / 已下载探测);
 * - 收藏态(驱动收藏 FAB 着色)。
 *
 * 列表内容(顶部大图 / header 区块 / 相关作品)全部归框架 [ceui.pixiv.feeds.FeedViewModel] +
 * [ArtworkV3FeedSource];完整详情 bean 的解析 / 拉取也由数据源负责,本 VM 只观察 ObjectPool
 * 里那条 bean 的落地来初始化 FAB / 收藏态。
 */
class ArtworkV3ViewModel(
    private val illustId: Long,
) : ViewModel() {

    private var illustBean: Illust? = null

    /**
     * 用户在本页主动开启的“加载原图”开关（跨旋转保留）。
     * 传给顶层大图 [IllustAdapter] 的 isForceOriginal，覆盖全局
     * isShowOriginalPreviewImage 之外的逐作品需求；见 ArtworkV3Fragment.showMoreMenu。
     */
    var forceOriginalPreview: Boolean = false

    private val _isBookmarked = MutableLiveData<Boolean>()
    val isBookmarked: LiveData<Boolean> = _isBookmarked

    private val illustBeanLiveData = ObjectPool.get<Illust>(illustId)

    private val illustBeanObserver = Observer<Illust> { bean ->
        illustBean = bean
        _isBookmarked.value = bean.isBookmarked
        ensurePageDimensions(bean)
        ensureTrustedCaption(bean)
        if (downloadFabActive && waitingForInitialBean) {
            refreshDownloadFab()
        }
    }

    // ── 每页真实宽高(网页 ajax /ajax/illust/{id}/pages)──
    // app-api 的 meta_pages 不带每 P 宽高;这里补上,供顶部大图在下载前按真 ratio 预置各页高度,
    // 消除多 P 首帧「兜底高→自然高」的跳。Fragment 观察 [pageDimensions] 喂给 IllustAdapter。
    private val _pageDimensions = MutableLiveData<List<IntArray>>()

    /** 每一 P 的 [width, height],按页序;缺 cookie / 接口失败则不发射(沿用解码后异步定高)。 */
    val pageDimensions: LiveData<List<IntArray>> = _pageDimensions

    private var pageDimsRequested = false

    /** 多 P 首次拿到 bean 时拉一次每页真实宽高(单 P 无需、只拉一次)。缺 cookie/失败静默降级。 */
    private fun ensurePageDimensions(bean: Illust) {
        if (pageDimsRequested || bean.page_count < 2) return
        pageDimsRequested = true
        viewModelScope.launch {
            fetchIllustPageDimensions(illustId)?.let { _pageDimensions.value = it }
        }
    }

    // ── caption 后台补拉(#960)──

    private var captionBackfillRequested = false
    private var pageVisible = false

    /**
     * 「这一页被用户真正打开过」——由 Fragment 的 onResume 调,一次性闸门(不在 onPause 复位:
     * 它守的是**一次性**补拉,语义是「够不够格发这一次请求」,不是「此刻是否在前台」)。
     *
     * [ceui.lisa.activities.VActivity] 用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT + offscreenPageLimit=1
     * 提前建好前后各一页的 Fragment/VM(为了横滑跟手),那两页只到 STARTED。补拉挂在 VM 创建上的话,
     * 一次横滑浏览会把**没打开过**的作品也各拉一遍 detail;挂在这里就只给当前页付钱。
     */
    fun onPageVisible() {
        pageVisible = true
        illustBean?.let { ensureTrustedCaption(it) }
    }

    /**
     * issue #960:pixiv 的列表接口会不定期对部分作品返回空 caption,详情页据此会漏掉简介。
     *
     * 补是要补,但**绝不能挂在首屏的阻塞路径上**——那正是这条曾经犯过的错:判据一度写进
     * [ArtworkV3FeedSource.resolveFullIllust],于是池里 isFullDetail=true(大图 / 作者 / tag /
     * 统计全都能立刻画出来)的 bean,只因为 caption 是空串就要整页挂起等 v1/illust/detail,
     * 首屏白屏转圈 0.6~1.1s;而空 caption 在推荐流里约占四成(真机采样 8 个中 3 个),
     * 且其中大多数作品是**真没有简介**,那一次请求纯属白等。
     *
     * 现在对齐 V2([ceui.lisa.fragments.FragmentIllustViewModel] 的 init):首屏照常用池里的 bean
     * 立刻渲染,detail 在后台拉;[fetchFullIllustDetail] 落池后由 Fragment 的 ObjectPool observer
     * 把简介块增量插回去(见 ArtworkV3Fragment.syncDescSection)。
     *
     * 只在「池里这条 bean 本来就够画首屏」时才补:[isFullDetail] 为 false 时数据源自己正在阻塞
     * 拉 detail,那一次拉取顺带就把 caption 带回来了,这里再发一次就是重复请求。
     *
     * 命中率(真机实测):动态流 8 次补拉救回 4 条简介(pixiv 确实会掐动态流的 caption);推荐流
     * 6 次全空——那些作品是**真没写简介**。所以这笔钱只花在「用户真的打开了、且这条确实缺简介」
     * 的作品上([onPageVisible] 的闸门),每个作品至多一次(拉过即进 fullVersionKeys)。
     */
    private fun ensureTrustedCaption(bean: Illust) {
        if (!pageVisible || captionBackfillRequested) return
        if (!bean.isFullDetail() || bean.hasTrustedCaption()) return
        captionBackfillRequested = true
        viewModelScope.launch { fetchFullIllustDetail(illustId) }
    }

    // ── download FAB state machine ──
    // 通过 Manager 队列串行下载;FAB 状态依赖 Manager 是否正在下载当前作品 + DB 是否已有下载记录。
    private var downloadFabActive = false
    private var waitingForInitialBean = false
    private val fabRefreshTick = MutableLiveData(0)

    private var downloadedCache: Boolean? = null
    private var downloadCheckJob: Job? = null

    private val _downloadFabState = MediatorLiveData<DownloadFab>().apply {
        value = DownloadFab.Idle
        addSource(fabRefreshTick) { recomputeFab() }
    }
    val downloadFabState: LiveData<DownloadFab> = _downloadFabState

    var isPollingProgress = false
        private set
    private var progressPollingJob: Job? = null

    init {
        illustBeanLiveData.observeForever(illustBeanObserver)
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
    }

    /**
     * 主下载 FAB。必须走带 activity 的重载:
     * - 用户在设置里选的「默认下载分辨率」由 [IllustDownload.defaultImageResolution] 兑现 ——
     *   不带分辨率的重载内部写死原图,等于把这条设置在 V3 详情页整个吞掉(经典页
     *   [ceui.lisa.fragments.FragmentIllust] 的下载按钮一直是尊重它的);
     * - activity 是 [IllustDownload.check] 的 SAF 闸门所必需:授权目录失效时要能弹出重选,
     *   否则整批入队后每一页都写盘失败。
     *
     * @param activity 宿主 activity;取不到时降级为不带闸门的调用(分辨率仍然生效)。
     */
    fun triggerDownload(activity: BaseActivity<*>?) {
        val illust = illustBean ?: return
        downloadCheckJob?.cancel()
        downloadedCache = null
        val resolution = IllustDownload.defaultImageResolution()
        if (activity != null) {
            IllustDownload.downloadIllustAllPagesWithResolution(illust, resolution, activity)
        } else {
            IllustDownload.downloadIllustAllPagesWithResolution(illust, resolution)
        }
        _downloadFabState.value = DownloadFab.Downloading(0)
        startProgressPolling(illust.page_count)
    }

    /**
     * 轮询 Manager 队列中当前 illust 的下载进度。只关心本作品的 DownloadItem。
     * 进度 = (已完成页 × 100 + 正在下载页的 nonius) / 总页数。
     */
    private fun startProgressPolling(pageCount: Int) {
        if (progressPollingJob?.isActive == true) return
        isPollingProgress = true
        progressPollingJob = viewModelScope.launch {
            while (isPollingProgress) {
                kotlinx.coroutines.delay(300)
                // contentSnapshot() 是带 synchronized 的浅拷贝;直接 .content 拿 live list 会 CME。
                val items = ceui.lisa.core.Manager.get().contentSnapshot()
                val myItems = items.filter { it.illust?.id == illustId }
                if (myItems.isEmpty()) {
                    // 队列清空 = 下载完成,直接设 Done,避免经过 Idle 闪烁
                    isPollingProgress = false
                    downloadedCache = true
                    // viewModelScope 在 Main 上；同步提交避免 postValue 排队期间被完成广播 /
                    // ViewPager 横滑触发的 refresh 插入，随后旧 Done 又反向覆盖新状态。
                    _downloadFabState.value = DownloadFab.Done
                    break
                }
                val remaining = myItems.size
                val completedPages = pageCount - remaining
                val activeItem = myItems.firstOrNull {
                    it.state == ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
                }
                val activeNonius = activeItem?.nonius ?: 0
                val totalPercent = if (pageCount > 0) {
                    ((completedPages * 100 + activeNonius) / pageCount).coerceIn(0, 99)
                } else 0
                _downloadFabState.value = DownloadFab.Downloading(totalPercent)
            }
        }
    }

    fun refreshDownloadFab() {
        downloadFabActive = true
        isPollingProgress = false
        progressPollingJob?.cancel()
        downloadCheckJob?.cancel()
        downloadedCache = null
        val bean = illustBean ?: run {
            waitingForInitialBean = true
            return
        }
        waitingForInitialBean = false
        val hasQueuedPages = ceui.lisa.core.Manager.get().contentSnapshot()
            .any { it.illust?.id == illustId }
        if (hasQueuedPages) {
            // 从后台/其它页面回来时下载可能仍在队列：直接恢复轮询，不要先显示 Idle 再查 DB。
            _downloadFabState.value = DownloadFab.Downloading(0)
            startProgressPolling(bean.page_count)
            return
        }
        fabRefreshTick.value = (fabRefreshTick.value ?: 0) + 1
    }

    /** ViewPager 切走/页面不可见时停掉 300ms 轮询和 DB 探测；恢复时 [refreshDownloadFab] 续上。 */
    fun pauseDownloadFab() {
        downloadFabActive = false
        waitingForInitialBean = false
        isPollingProgress = false
        progressPollingJob?.cancel()
        downloadCheckJob?.cancel()
    }

    private fun recomputeFab() {
        // 被 ViewPager 降到 STARTED 后，即使某个已进入 IO 的旧探测以异常/结果返回，也不能
        // 再从回调链启动下一次 DB 探测；当前页 onResume 会重新完整刷新。
        if (!downloadFabActive) return
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
        if (!downloadFabActive) return
        if (downloadCheckJob?.isActive == true) return
        val bean = illustBean ?: return
        downloadCheckJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
                    // hasDownloadRecord 走 v38 的 illustId 索引(O(log n));存量回填未完成时退回旧 LIKE 兜底。
                    Common.isIllustDownloaded(bean) ||
                            withContext(downloadProbeDispatcher) {
                                dao.hasDownloadRecord(bean.id)
                            }
                }
                downloadedCache = result
                recomputeFab()
            } catch (ce: CancellationException) {
                // ViewModel 清理时取消是控制流，不能吞成“未下载”再继续写 LiveData。
                throw ce
            } catch (e: Exception) {
                Timber.e(e, "downloaded check failed")
                downloadedCache = false
                recomputeFab()
            }
        }
    }
}

sealed interface DownloadFab {
    data object Idle : DownloadFab
    data class Downloading(val percent: Int) : DownloadFab
    data object Done : DownloadFab
}
