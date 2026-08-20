package ceui.lisa.fragments

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.downloadProbeDispatcher
import ceui.lisa.database.hasDownloadRecord
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import ceui.loxia.fetchFullIllustDetail
import ceui.loxia.fetchIllustPageDimensions
import ceui.loxia.hasTrustedCaption
import ceui.loxia.isFullDetail
import ceui.pixiv.ui.common.FollowStateBackfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * VM for the "new" illust detail page (FragmentIllust).
 *
 * 关注态回补只在页面划入（onResume）后开始：
 * - 非预载页：进入时立即开始；
 * - 预载页：滑到该页后才开始，避免提前请求/提前进入“查询中”。
 */
class FragmentIllustViewModel(private val illustId: Long) : ViewModel() {

    private val _hasDownload = MutableLiveData<Boolean>()
    val hasDownload: LiveData<Boolean> = _hasDownload

    // ── 每页真实宽高 ──
    private val _pageDimensions = MutableLiveData<List<IntArray>>()
    val pageDimensions: LiveData<List<IntArray>> = _pageDimensions

    private var pageDimsRequested = false

    /** 关注态 UI 的主动状态：只承载“回补进行中”，关注值一律以 ObjectPool 的 UserBean 为准。 */
    data class FollowUiState(val loading: Boolean)

    private val _followState = MutableLiveData(FollowUiState(false))
    val followState: LiveData<FollowUiState> = _followState

    private val _followStateLoading = MutableLiveData(false)
    val followStateLoading: LiveData<Boolean> = _followStateLoading

    /** 回补完成后的主动刷新 tick：Fragment 观察到后立即用最新池数据重刷作者栏。 */
    private val _followStateRefresh = MutableLiveData(0)
    val followStateRefresh: LiveData<Int> = _followStateRefresh

    private var followBackfillRequested = false
    private var fullDetailFetchInFlight = false
    private var pageVisible = false

    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)
    private val illustBeanObserver = Observer<IllustsBean> { bean ->
        ensurePageDimensions(bean)
        // 划入后如果池 bean 才到达/更新，也要能触发回补；未划入时不做任何预载回补。
        if (pageVisible) startBackfills()
    }

    init {
        // 回补不在 VM 创建时触发，统一由 Fragment.onResume（划入页面）调用 onPageVisible 后开始。
        illustBeanLiveData.observeForever(illustBeanObserver)
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
    }

    /** 多 P 首次拿到 bean 时拉一次每页真实宽高。 */
    private fun ensurePageDimensions(bean: IllustsBean) {
        if (pageDimsRequested || bean.page_count < 2) return
        pageDimsRequested = true
        viewModelScope.launch {
            fetchIllustPageDimensions(illustId)?.let { _pageDimensions.value = it }
        }
    }

    /** 把“回补进行中”状态主动推给 Fragment；关注值由池里的 UserBean 决定，VM 不缓存。 */
    private fun publishFollowState(loading: Boolean) {
        _followState.value = FollowUiState(loading)
        _followStateLoading.value = loading
    }

    private fun ensureTrustedFollow(bean: IllustsBean) {
        val illustId = bean.id.toLong()
        if (FollowStateBackfill.isIllustUntrusted(illustId)) {
            if (!followBackfillRequested && !FollowStateBackfill.isIllustTrusted(illustId)) {
                publishFollowState(true)
            }
        } else {
            FollowStateBackfill.markIllustTrusted(illustId)
            if (_followStateLoading.value == true) {
                publishFollowState(false)
            }
            Timber.tag("FollowState").d("信任池: 非自建源入口 illust=%d", illustId)
        }
    }

    private fun startFollowBackfill(bean: IllustsBean) {
        val illustId = bean.id.toLong()
        if (followBackfillRequested) {
            if (_followStateLoading.value == true && !FollowStateBackfill.isIllustUntrusted(illustId)) {
                publishFollowState(false)
            }
            return
        }
        if (!FollowStateBackfill.isIllustUntrusted(illustId)) {
            if (_followStateLoading.value == true) {
                publishFollowState(false)
            }
            return
        }
        followBackfillRequested = true

        if (FollowStateBackfill.isIllustTrusted(illustId) || ObjectPool.hasFullIllustVersion(illustId)) {
            Timber.tag("FollowState").d("信任池: illust=%d 直接使用池并标记已确认", illustId)
            FollowStateBackfill.markIllustConfirmed(illustId)
            publishFollowState(false)
            _followStateRefresh.value = (_followStateRefresh.value ?: 0) + 1
            return
        }

        publishFollowState(true)
        ensureFullDetailBackfill()
    }

    /**
     * 统一的全量 detail 回补入口：caption/完整度补拉与关注态回补共用同一个在途闸门，
     * 避免进入详情页时并发多个 v1/illust/detail 请求。
     */
    private fun ensureFullDetailBackfill() {
        if (fullDetailFetchInFlight) return
        fullDetailFetchInFlight = true
        viewModelScope.launch {
            try {
                val fresh = fetchFullIllustDetail(illustId)
                if (FollowStateBackfill.isIllustUntrusted(illustId)) {
                    if (fresh != null) {
                        FollowStateBackfill.markIllustConfirmed(illustId)
                        publishFollowState(false)
                        Timber.tag("FollowState").d("关注态回补成功: illust=%d", illustId)
                    } else {
                        publishFollowState(false)
                        Timber.tag("FollowState").w("关注态回补失败: illust=%d", illustId)
                    }
                    _followStateRefresh.value = (_followStateRefresh.value ?: 0) + 1
                } else if (_followStateLoading.value == true) {
                    publishFollowState(false)
                    _followStateRefresh.value = (_followStateRefresh.value ?: 0) + 1
                }
            } finally {
                fullDetailFetchInFlight = false
            }
        }
    }

    private fun startBackfills() {
        val bean = ObjectPool.get<IllustsBean>(illustId).value ?: return
        val illustId = bean.id.toLong()

        // caption/完整度需要回源时也在这里触发（与关注态共用同一在途闸门）。
        if (!bean.isFullDetail() || !bean.hasTrustedCaption()) {
            ensureFullDetailBackfill()
        }

        if (FollowStateBackfill.isIllustUntrusted(illustId)) {
            if (!followBackfillRequested) {
                ensureTrustedFollow(bean)
                startFollowBackfill(bean)
            } else if (_followStateLoading.value == true && !FollowStateBackfill.isIllustUntrusted(illustId)) {
                publishFollowState(false)
            }
        } else {
            FollowStateBackfill.markIllustTrusted(illustId)
            publishFollowState(false)
        }
    }

    /** 页面划入（onResume）时调用：非预载页立即开始，预载页滑到后才开始。 */
    fun onPageVisible() {
        pageVisible = true
        startBackfills()
    }

    /** Kick off an async download-state refresh. Result lands on [hasDownload]. */
    fun refreshDownloadState(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val illust = ObjectPool.get<IllustsBean>(illustId).value
                    ?: return@launch
                val hasLocalFile = Common.isIllustDownloaded(illust)
                val hasRecord = if (hasLocalFile) false else withContext(downloadProbeDispatcher) {
                    AppDatabase
                        .getAppDatabase(appContext)
                        .downloadDao()
                        .hasDownloadRecord(illust.id.toLong())
                }
                _hasDownload.postValue(hasLocalFile || hasRecord)
            } catch (e: Exception) {
                Timber.w(e, "refreshDownloadState failed illustId=%d", illustId)
            }
        }
    }

    class Factory(private val illustId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FragmentIllustViewModel(illustId) as T
        }
    }
}