package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.network.PlazaPost
import ceui.lisa.network.PlazaResult
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 广场 feed 的状态 + 行为 ViewModel。
 *
 * 分页:服务端 cursor = `before` (上一页最后一条的 id),`next_before == null`
 * 即 end-of-list (转为 [PlazaPagingState.EndReached])。
 *
 * 删帖立即从本地 list 移除 (optimistic) —— server 返回 404 也认账(按 spec
 * 404 防 enumeration,不区分"不存在 / 不是你发的 / 已删")。
 */
class PlazaViewModel(
    private val repository: PlazaRepository = PlazaRepository(),
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
) : ViewModel() {

    data class UiState(
        val items: List<PlazaPost> = emptyList(),
        val isInitialLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val paging: PlazaPagingState = PlazaPagingState.Idle,
        val initialError: String? = null,
    )

    sealed class PlazaPagingState {
        data object Idle : PlazaPagingState()
        data object LoadingMore : PlazaPagingState()
        data object EndReached : PlazaPagingState()
        data class Error(val message: String) : PlazaPagingState()
    }

    /** One-shot UI events (toast / snackbar). */
    sealed class Event {
        data class Toast(val message: String) : Event()
    }

    private val _state = MutableStateFlow(UiState(isInitialLoading = true))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    private var nextBefore: Long? = null
    private var loadingMore = false
    private var loaded = false
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var readGeneration = 0
    // Only changes made during the current read need to overlay its stale response.
    private val changedDuringRead = linkedMapOf<Long, PlazaPost>()
    private val removedDuringRead = mutableSetOf<Long>()
    private val pendingDeletes = mutableSetOf<Long>()

    fun ensureLoaded(context: Context) {
        if (!loaded && refreshJob?.isActive != true) load(context)
    }

    private fun reconcile(items: List<PlazaPost>): List<PlazaPost> =
        (changedDuringRead.values.toList() + items)
            .distinctBy { it.id }
            .filter { it.id !in removedDuringRead && it.id !in pendingDeletes }
            .sortedByDescending { it.id }


    init {
        // 订阅进程级 "新帖事件总线" —— 任何路径(包括另一个 Activity 里的 Compose Fragment)
        // 成功发帖,Plaza 立即 prepend 到顶。比 setFragmentResult / startActivityForResult
        // 路径更解耦,跟 chat 的 SharedFlow incoming 模式一致。
        // filter id 去重防止用户在打开 plaza 后立即发帖、又恰好下次 listFeed 把同条带回。
        viewModelScope.launch {
            repository.plazaPostsCreated.collect { newPost ->
                changedDuringRead[newPost.id] = newPost
                val current = _state.value.items
                if (current.any { it.id == newPost.id }) return@collect
                _state.value = _state.value.copy(items = listOf(newPost) + current)
            }
        }
        // 订阅删帖事件 —— detail 页 / 别处删了一条,Plaza 同步 filter 掉。
        // 本 VM 自己触发的删除走 deletePost() 已 optimistic 移除,这里再 filter
        // 一次也是 no-op (幂等)。
        viewModelScope.launch {
            repository.plazaPostsDeleted.collect { deletedId ->
                removedDuringRead += deletedId
                changedDuringRead.remove(deletedId)
                val current = _state.value.items
                if (current.none { it.id == deletedId }) return@collect
                _state.value = _state.value.copy(items = current.filter { it.id != deletedId })
            }
        }
        // 订阅互动更新事件 —— 详情页 toggle like / 发评论后,本 VM 同步对应 item
        // 的 like_count / comment_count / liked_by_viewer,不需要全量重拉 feed。
        viewModelScope.launch {
            repository.plazaPostsUpdated.collect { updated ->
                val current = _state.value.items
                val idx = current.indexOfFirst { it.id == updated.id }
                if (idx < 0) return@collect
                changedDuringRead[updated.id] = updated
                _state.value = _state.value.copy(
                    items = current.toMutableList().also { it[idx] = updated },
                )
            }
        }
    }

    fun load(context: Context, isSwipeRefresh: Boolean = false) {
        if (refreshJob?.isActive == true) return
        val generation = ++readGeneration
        pageJob?.cancel()
        loadingMore = false
        changedDuringRead.clear()
        removedDuringRead.clear()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isInitialLoading = !isSwipeRefresh && _state.value.items.isEmpty(),
                isRefreshing = isSwipeRefresh,
                initialError = null,
                paging = PlazaPagingState.Idle,
            )
            when (val r = repository.listPlazaFeed(
                limit = 20, before = null,
                viewerUid = currentUid(),
            )) {
                is PlazaResult.Ok -> {
                    if (generation != readGeneration) return@launch
                    loaded = true
                    nextBefore = r.value.next_before
                    _state.value = _state.value.copy(
                        items = reconcile(r.value.items),
                        isInitialLoading = false,
                        isRefreshing = false,
                        paging = if (r.value.next_before == null) PlazaPagingState.EndReached
                        else PlazaPagingState.Idle,
                        initialError = null,
                    )
                }
                is PlazaResult.Err -> {
                    val msg = mapErrorMessage(context, r)
                    _state.value = _state.value.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        initialError = if (_state.value.items.isEmpty()) msg else null,
                    )
                    if (_state.value.items.isNotEmpty()) {
                        _events.trySend(Event.Toast(context.getString(R.string.plaza_load_failed, msg)))
                    }
                }
            }
        }
    }

    fun loadMore(context: Context) {
        val before = nextBefore ?: return  // null = 上次拿到 next_before==null,等价 EndReached
        if (loadingMore || refreshJob?.isActive == true) return
        val generation = ++readGeneration
        changedDuringRead.clear()
        removedDuringRead.clear()
        loadingMore = true
        pageJob = viewModelScope.launch {
            try {
                _state.value = _state.value.copy(paging = PlazaPagingState.LoadingMore)
                when (val r = repository.listPlazaFeed(
                    limit = 20, before = before,
                    viewerUid = currentUid(),
                )) {
                    is PlazaResult.Ok -> {
                        if (generation != readGeneration) return@launch
                        nextBefore = r.value.next_before
                        val merged = reconcile(_state.value.items + r.value.items)
                        _state.value = _state.value.copy(
                            items = merged,
                            paging = if (r.value.next_before == null) PlazaPagingState.EndReached
                            else PlazaPagingState.Idle,
                        )
                    }
                    is PlazaResult.Err -> {
                        _state.value = _state.value.copy(
                            paging = PlazaPagingState.Error(mapErrorMessage(context, r)),
                        )
                    }
                }
            } finally {
                if (generation == readGeneration) loadingMore = false
            }
        }
    }

    /**
     * Optimistic delete:本地立即移除,然后异步发删除请求。
     * - server 200 / 404:认账(404 spec 防 enumeration,语义上"已经不在了")
     * - 临时错(rate_limit / network 等):**回滚**,把 post 按原 index 插回去,
     *   弹 toast 让用户重试。否则 user 看到"成功删除",实际 server 上还在,
     *   下次刷新它"复活"形成 ghost-recovery 困惑。
     */
    fun deletePost(context: Context, post: PlazaPost, selfUid: Long) {
        if (selfUid <= 0L || post.uid != selfUid) return
        val originalItems = _state.value.items
        val originalIndex = originalItems.indexOfFirst { it.id == post.id }
        if (originalIndex < 0) return  // 列表里已经没这条,nothing to do
        pendingDeletes += post.id
        changedDuringRead.remove(post.id)
        _state.value = _state.value.copy(items = originalItems.filter { it.id != post.id })
        viewModelScope.launch {
            val r = repository.deletePlazaPost(selfUid, post.id)
            pendingDeletes -= post.id
            if (r is PlazaResult.Ok || (r is PlazaResult.Err && r.code == "not_found")) {
                removedDuringRead += post.id
            }
            when (r) {
                is PlazaResult.Ok -> {
                    _events.trySend(Event.Toast(context.getString(R.string.plaza_post_deleted)))
                }
                is PlazaResult.Err -> {
                    if (r.code == "not_found") {
                        _events.trySend(Event.Toast(context.getString(R.string.plaza_post_deleted)))
                    } else {
                        // 临时错 —— 回滚:把 post 插回原 index。注意此时 items
                        // 可能已被其它路径变化(很少见但要兜底),按当前 items
                        // 长度 coerce index。
                        val current = _state.value.items
                        val safeIndex = originalIndex.coerceIn(0, current.size)
                        val rolled = current.toMutableList().apply {
                            if (none { it.id == post.id }) add(safeIndex, post)
                        }
                        _state.value = _state.value.copy(items = rolled)
                        val msg = mapErrorMessage(context, r)
                        _events.trySend(
                            Event.Toast(context.getString(R.string.plaza_post_delete_failed, msg))
                        )
                    }
                }
            }
        }
    }

    private fun mapErrorMessage(context: Context, err: PlazaResult.Err): String {
        return when (err.code) {
            "rate_limited" -> {
                val s = err.body?.retryAfterSeconds ?: 60
                context.getString(R.string.plaza_rate_limited, s.toInt())
            }
            "ts_skew" -> context.getString(R.string.plaza_ts_skew)
            "network" -> context.getString(R.string.chat_error_network_unavailable)
            else -> err.code
        }
    }
}
