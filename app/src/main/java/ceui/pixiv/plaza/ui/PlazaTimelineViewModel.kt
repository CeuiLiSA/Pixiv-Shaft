package ceui.pixiv.plaza.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import ceui.pixiv.feeds.cache.feedCacheWriteScope
import ceui.pixiv.plaza.PlazaPage
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.plaza.PlazaPostCacheEntry
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

internal data class TimelineState(
    val items: List<PlazaPost> = emptyList(),
    val parent: PlazaPost? = null,
    val loading: Boolean = true,
    val restoringCache: Boolean = true,
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
    private val firstPageCache: (Boolean, Long) -> FeedFirstPageStore<PlazaPage>? =
        PlazaRepository::firstPageCache,
    private val cacheWriteScope: CoroutineScope = feedCacheWriteScope,
) : ViewModel() {
    private fun requireAccount(expected: Long) {
        if (expected <= 0 || expected != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    private val postObservers = mutableMapOf<Long, Pair<LiveData<PlazaPostCacheEntry>, Observer<PlazaPostCacheEntry>>>()
    private var refreshPendingForcePost: Boolean? = null
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
    private var restoredFirstPage = false

    fun enter(id: Long = 0) {
        val account = currentUid()
        if (uid != account || postId != id) {
            refreshPendingForcePost = null
            request?.cancel()
            mutation?.cancel()
            clearPostObservers()
            uid = account
            postId = id
            mutable.value = TimelineState(
                parent = id.takeIf { it > 0 }?.let { PlazaRepository.cachedEntry(it, account)?.post },
                restoringCache = id == 0L,
            )
            observedRevision = -1
            restoredFirstPage = false
            observePosts()
        }
        if (
            observedRevision != PlazaRepository.revision.value ||
                System.currentTimeMillis() - loadedAt > 240_000 ||
                (postId > 0 && !PlazaRepository.hasFreshPost(postId, account)) ||
                (mutable.value.loading && request?.isActive != true)
        )
            refresh(forcePost = false)
    }

    fun selectMine(value: Boolean) {
        if (mine == value) return
        mine = value
        request?.cancel()
        restoredFirstPage = false
        mutable.value = TimelineState()
        observePosts()
        refresh()
    }

    fun refresh(forcePost: Boolean = true) {
        if (mutable.value.busyIds.isNotEmpty()) {
            refreshPendingForcePost = forcePost || refreshPendingForcePost == true
            return
        }
        request?.cancel()
        val account = uid
        val requestRevision = PlazaRepository.revision.value
        observedRevision = requestRevision
        mutable.value = mutable.value.copy(
            loading = true, loadingMore = false, error = null,
            restoringCache = postId == 0L && !restoredFirstPage,
        )
        request = viewModelScope.launch {
            var fetchingParent = false
            try {
                requireAccount(account)
                val cache = if (postId == 0L) firstPageCache(mine, account) else null
                if (!restoredFirstPage && cache != null) {
                    val snapshot = try {
                        cache.read()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "plaza: first-page cache read failed")
                        null
                    }
                    requireAccount(account)
                    restoredFirstPage = true
                    if (snapshot != null && mutable.value.items.isEmpty()) {
                        val restored = try {
                            PlazaRepository.restorePage(snapshot.payload, account, snapshot.savedAtMillis)
                        } catch (e: Exception) {
                            Timber.w(e, "plaza: malformed first-page snapshot")
                            null
                        }
                        if (restored != null) {
                            mutable.value = mutable.value.copy(
                                items = restored,
                                next = snapshot.nextCursor?.toLongOrNull(),
                            )
                            observePosts()
                        }
                    }
                }
                mutable.value = mutable.value.copy(restoringCache = false)
                if (postId > 0) {
                    val parent = if (!forcePost && PlazaRepository.hasFreshPost(postId, account)) {
                        checkNotNull(PlazaRepository.cachedEntry(postId, account)?.post)
                    } else {
                        fetchingParent = true
                        val fetched = api.post(postId)
                        fetchingParent = false
                        requireAccount(account)
                        if (requestRevision != PlazaRepository.revision.value) {
                            refresh(forcePost = false)
                            return@launch
                        }
                        PlazaRepository.cache(fetched, account)
                        fetched
                    }
                    // A cold-link post is also visible before the comments request finishes.
                    mutable.value = mutable.value.copy(parent = parent)
                }
                val page =
                    api.feed(
                        author = if (mine && postId == 0L) account else null,
                        replyTo = postId.takeIf { it > 0 },
                    )
                requireAccount(account)
                if (requestRevision != PlazaRepository.revision.value) {
                    refresh(forcePost = false)
                    return@launch
                }
                page.items.forEach { PlazaRepository.cache(it, account) }
                mutable.value =
                    mutable.value.copy(
                        items = page.items,
                        next = page.nextBefore,
                        loading = false,
                    )
                observePosts()
                loadedAt = System.currentTimeMillis()
                // Only successful network first pages are persisted; appends never replace the slot.
                if (cache != null) cacheWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    PlazaRepository.persistFirstPage(cache, page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (fetchingParent && account == currentUid() &&
                    e is retrofit2.HttpException && e.code() == 404) {
                    // Reject older list/comment responses that could otherwise revive this entry.
                    PlazaRepository.changed()
                    PlazaRepository.invalidate(postId, account)
                }
                mutable.value = mutable.value.copy(loading = false, restoringCache = false, error = plazaError(e))
            }
        }
    }

    fun more() {
        val s = mutable.value
        val cursor = s.next ?: return
        if (s.loading || s.loadingMore || s.error != null || s.busyIds.isNotEmpty()) return
        mutable.value = s.copy(loadingMore = true)
        val account = uid
        val requestRevision = PlazaRepository.revision.value
        request = viewModelScope.launch {
            try {
                requireAccount(account)
                val page =
                    api.feed(
                        before = cursor,
                        author = if (mine && postId == 0L) uid else null,
                        replyTo = postId.takeIf { it > 0 },
                    )
                requireAccount(account)
                if (requestRevision != PlazaRepository.revision.value) {
                    refresh(forcePost = false)
                    return@launch
                }
                page.items.forEach { PlazaRepository.cache(it, account) }
                mutable.value =
                    mutable.value.copy(
                        items = (mutable.value.items + page.items).distinctBy { it.id },
                        next = page.nextBefore,
                        loadingMore = false,
                    )
                observePosts()
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
                    ?: throw PlazaFailure(PlazaMessage(R.string.plaza_not_found))
            if (current.liked) api.unlike(post.id) else api.like(post.id)
        }

    fun react(post: PlazaPost, emoji: String) =
        mutate(post.id) {
            val current =
                mutable.value.items.find { it.id == post.id }
                    ?: mutable.value.parent?.takeIf { it.id == post.id }
                    ?: throw PlazaFailure(PlazaMessage(R.string.plaza_not_found))
            val stickerId = emoji.takeIf { it.startsWith("sticker:") }?.substringAfter(':')?.toLongOrNull()
            val selected = current.reactions.any { it.emoji == emoji && it.selected }
            if (stickerId != null) {
                if (selected) api.unreactSticker(post.id, stickerId) else api.reactSticker(post.id, stickerId)
            } else {
                if (selected) api.unreact(post.id, emoji) else api.react(post.id, emoji)
            }
        }

    fun delete(post: PlazaPost) =
        mutate(post.id) {
            check(api.delete(post.id).ok)
            null
        }

    private fun mutate(id: Long, action: suspend () -> PlazaPost?) {
        if (mutable.value.busyIds.isNotEmpty()) return
        // A cached post is already interactive while comments/the first page refresh in the
        // background. Keep that request alive; revision checks reject any stale response.
        if (!mutable.value.loading) request?.cancel()
        mutable.value = mutable.value.copy(busyIds = setOf(id), loadingMore = false, error = null)
        val account = uid
        val route = postId
        mutation = viewModelScope.launch {
            try {
                PlazaRepository.mutate {
                    requireAccount(account)
                    val updated = action()
                    requireAccount(account)
                    PlazaRepository.changed()
                    if (updated != null) PlazaRepository.cache(updated, account)
                    else PlazaRepository.invalidate(id, account)
                    if (updated == null && id == postId) {
                        request?.cancel()
                        refreshPendingForcePost = null
                        mutable.value = mutable.value.copy(loading = false, restoringCache = false, next = null)
                    }
                    observePosts()
                    observedRevision = PlazaRepository.revision.value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = plazaError(e))
            } finally {
                if (uid == account && postId == route) {
                    mutable.value = mutable.value.copy(busyIds = mutable.value.busyIds - id)
                    refreshPendingForcePost?.let { forcePost ->
                        refreshPendingForcePost = null
                        refresh(forcePost)
                    }
                }
            }
        }
    }

    private val imageRefreshAt = mutableMapOf<Long, Long>()

    /**
     * Signed media URLs outlive the screen's own refresh window when the feed simply stays open.
     * A post scrolled into view with an expired signature is re-fetched on its own, and the
     * shared entry update swaps the URLs in place; Glide keeps already-cached bytes regardless.
     * One attempt per post per window, so a device clock far ahead of the server cannot loop.
     */
    fun ensureFreshImages(post: PlazaPost) {
        val now = System.currentTimeMillis()
        if (post.images.none { it.expiresAt <= now + IMAGE_EXPIRY_MARGIN_MS }) return
        val last = imageRefreshAt[post.id]
        if (last != null && now - last < IMAGE_REFRESH_INTERVAL_MS) return
        imageRefreshAt[post.id] = now
        val account = uid
        viewModelScope.launch {
            try {
                requireAccount(account)
                val fresh = api.post(post.id)
                requireAccount(account)
                PlazaRepository.cache(fresh, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 404 && account == currentUid()) {
                    PlazaRepository.changed()
                    PlazaRepository.invalidate(post.id, account)
                }
                // The stale tile already shows its error placeholder; a pull refresh still works.
                Timber.d(e, "plaza: media signature refresh failed for post %d", post.id)
            }
        }
    }

    /** Observe the same ObjectPool entries as other lists and detail pages, like artworks. */
    private fun observePosts() {
        val ids = (mutable.value.items.map { it.id } + listOfNotNull(postId.takeIf { it > 0 })).toSet()
        (postObservers.keys - ids).forEach { id ->
            postObservers.remove(id)?.let { (live, observer) -> live.removeObserver(observer) }
        }
        (ids - postObservers.keys).forEach { id ->
            val live = ObjectPool.get<PlazaPostCacheEntry>(id)
            val observer = Observer<PlazaPostCacheEntry> { entry ->
                if (entry.viewerUid != uid || uid != currentUid()) return@Observer
                mutable.value = mutable.value.copy(
                    parent = if (id == postId) entry.post else mutable.value.parent,
                    items = if (id == postId && entry.post == null) emptyList()
                        else mutable.value.items.mapNotNull { if (it.id == id) entry.post else it },
                )
            }
            postObservers[id] = live to observer
            live.observeForever(observer)
        }
    }

    private fun clearPostObservers() {
        postObservers.values.forEach { (live, observer) -> live.removeObserver(observer) }
        postObservers.clear()
    }

    override fun onCleared() {
        clearPostObservers()
        super.onCleared()
    }

    private companion object {
        /** Same margin as PlazaImageSource: a URL about to expire is treated as expired. */
        const val IMAGE_EXPIRY_MARGIN_MS = 5_000L
        const val IMAGE_REFRESH_INTERVAL_MS = 30_000L
    }
}
