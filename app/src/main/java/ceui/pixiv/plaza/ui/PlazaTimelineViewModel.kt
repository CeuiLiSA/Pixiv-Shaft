package ceui.pixiv.plaza.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class TimelineState(
    val items: List<PlazaPost> = emptyList(),
    val parent: PlazaPost? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val next: Long? = null,
    val error: PlazaMessage? = null,
    val busyIds: Set<Long> = emptySet(),
)

internal class PlazaTimelineViewModel
@JvmOverloads
constructor(
    private val saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
) : ViewModel() {
    private fun requireAccount(expected: Long) {
        if (expected <= 0 || expected != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    private var refreshPending = false
    private var mutation: Job? = null
    private val mutable = MutableStateFlow(TimelineState())
    val state = mutable.asStateFlow()
    private var presentedError: PlazaMessage? = null

    fun takeErrorForAlert(): PlazaMessage? {
        val error = state.value.error ?: return null
        if (error === presentedError) return null
        presentedError = error
        return error
    }

    var mine: Boolean
        get() = saved["mine"] ?: false
        private set(value) {
            saved["mine"] = value
        }

    private var postId = 0L
    private var uid = 0L
    private var request: Job? = null
    private var observedRevision = -1L
    private var loadedAt = 0L

    fun enter(id: Long = 0) {
        val account = currentUid()
        if (uid != account || postId != id) {
            request?.cancel()
            mutation?.cancel()
            mutable.value = TimelineState()
            uid = account
            postId = id
            observedRevision = -1
        }
        if (
            observedRevision != PlazaRepository.revision.value ||
                System.currentTimeMillis() - loadedAt > 240_000 ||
                (mutable.value.loading && request?.isActive != true)
        )
            refresh()
    }

    fun selectMine(value: Boolean) {
        if (mine == value) return
        mine = value
        request?.cancel()
        mutable.value = TimelineState()
        refresh()
    }

    fun refresh() {
        if (mutable.value.busyIds.isNotEmpty()) {
            refreshPending = true
            return
        }
        request?.cancel()
        val account = uid
        observedRevision = PlazaRepository.revision.value
        mutable.value = mutable.value.copy(loading = true, loadingMore = false, error = null)
        request = viewModelScope.launch {
            try {
                requireAccount(account)
                val parent = if (postId > 0) api.post(postId) else null
                val page =
                    api.feed(
                        author = if (mine && postId == 0L) account else null,
                        replyTo = postId.takeIf { it > 0 },
                    )
                requireAccount(account)
                mutable.value =
                    mutable.value.copy(
                        items = page.items,
                        parent = parent,
                        next = page.nextBefore,
                        loading = false,
                    )
                loadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = plazaError(e))
            }
        }
    }

    fun more() {
        val s = mutable.value
        val cursor = s.next ?: return
        if (s.loading || s.loadingMore || s.error != null || s.busyIds.isNotEmpty()) return
        mutable.value = s.copy(loadingMore = true)
        request = viewModelScope.launch {
            try {
                requireAccount(uid)
                val page =
                    api.feed(
                        before = cursor,
                        author = if (mine && postId == 0L) uid else null,
                        replyTo = postId.takeIf { it > 0 },
                    )
                requireAccount(uid)
                mutable.value =
                    mutable.value.copy(
                        items = (mutable.value.items + page.items).distinctBy { it.id },
                        next = page.nextBefore,
                        loadingMore = false,
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(loadingMore = false, error = plazaError(e))
            }
        }
    }

    fun like(post: PlazaPost) =
        mutate(post.id) {
            val current =
                mutable.value.items.find { it.id == post.id }
                    ?: mutable.value.parent?.takeIf { it.id == post.id }
                    ?: return@mutate
            val updated = if (current.liked) api.unlike(post.id) else api.like(post.id)
            mutable.value =
                mutable.value.copy(
                    items = mutable.value.items.map { if (it.id == post.id) updated else it },
                    parent = mutable.value.parent?.let { if (it.id == post.id) updated else it },
                )
        }

    fun react(post: PlazaPost, emoji: String) =
        mutate(post.id) {
            val current =
                mutable.value.items.find { it.id == post.id }
                    ?: mutable.value.parent?.takeIf { it.id == post.id }
                    ?: return@mutate
            val updated =
                if (current.reactions.any { it.emoji == emoji && it.selected })
                    api.unreact(post.id, emoji)
                else api.react(post.id, emoji)
            mutable.value =
                mutable.value.copy(
                    items = mutable.value.items.map { if (it.id == post.id) updated else it },
                    parent = mutable.value.parent?.let { if (it.id == post.id) updated else it },
                )
        }

    fun delete(post: PlazaPost) =
        mutate(post.id) {
            check(api.delete(post.id).ok)
            mutable.value =
                mutable.value.copy(
                    items = mutable.value.items.filterNot { it.id == post.id },
                    parent = mutable.value.parent?.takeUnless { it.id == post.id },
                )
        }

    private fun mutate(id: Long, action: suspend () -> Unit) {
        if (mutable.value.busyIds.isNotEmpty() || mutable.value.loading) return
        request?.cancel()
        mutable.value = mutable.value.copy(busyIds = setOf(id), loadingMore = false, error = null)
        mutation = viewModelScope.launch {
            try {
                requireAccount(uid)
                action()
                requireAccount(uid)
                PlazaRepository.changed()
                observedRevision = PlazaRepository.revision.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = plazaError(e))
            } finally {
                mutable.value = mutable.value.copy(busyIds = mutable.value.busyIds - id)
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }
}
