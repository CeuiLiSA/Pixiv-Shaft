package ceui.pixiv.plaza.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import ceui.pixiv.feeds.cache.feedCacheWriteScope
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.plaza.PlazaPage
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** One feed row. [busy] marks a post whose like / reaction / delete request is in flight. */
internal data class PlazaPostItem(val post: PlazaPost, val busy: Boolean = false) : FeedItem {
    override val feedKey: Any
        get() = post.id
}

/** ViewBinding shell so the code-built [PostView] can be a feeds renderer cell. */
internal class PostViewBinding(private val view: PostView) : ViewBinding {
    override fun getRoot(): PostView = view
}

/**
 * Plaza feed on the feeds framework. Pages come from the Tokyo Bearer API; the first page of
 * each scope (all / mine, per account) is mirrored in the shared Room first-page store.
 *
 * Every request pins the account at its start and rejects the response if the account changed
 * while it was in flight. A response that overlaps a mutation (revision moved) is fetched
 * again rather than allowed to overwrite newer pooled state. Successful posts are written to
 * the ObjectPool so list, detail and other screens observe one entry per post.
 */
internal class PlazaFeedSource(
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val mine: () -> Boolean,
    private val firstPageCache: (Boolean, Long) -> FeedFirstPageStore<PlazaPage>? =
        PlazaRepository::firstPageCache,
    private val cacheWriteScope: CoroutineScope = feedCacheWriteScope,
    private val onFirstPage: (revision: Long) -> Unit = {},
) : FeedSource<Long> {

    private fun requireAccount(expected: Long) {
        if (expected <= 0 || expected != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    /** Disk snapshot of this scope's first page; malformed or foreign snapshots are a miss. */
    override suspend fun loadFromCache(): FeedPage<Long>? {
        val account = currentUid()
        if (account <= 0) return null
        val store = firstPageCache(mine(), account) ?: return null
        val safetyRevision = PlazaRepository.safetyRevision.value
        val snapshot = store.read() ?: return null
        if (safetyRevision != PlazaRepository.safetyRevision.value) return null
        // The account may have switched during the disk read: that snapshot must neither be
        // shown to nor pooled for the new account.
        requireAccount(account)
        val restored = PlazaRepository.restorePage(snapshot.payload, account, snapshot.savedAtMillis)
        if (restored.isEmpty()) return null
        return FeedPage(restored.map { PlazaPostItem(it) }, snapshot.nextCursor?.toLongOrNull())
    }

    override suspend fun load(cursor: Long?): FeedPage<Long> {
        val account = currentUid()
        requireAccount(account)
        val own = mine()
        var page: PlazaPage
        while (true) {
            val revision = PlazaRepository.revision.value
            page = api.feed(before = cursor, author = if (own) account else null)
            requireAccount(account)
            // A mutation landed while this page was in flight; its posts are already stale.
            if (revision == PlazaRepository.revision.value) break
        }
        page.items.forEach { PlazaRepository.cache(it, account) }
        if (cursor == null) {
            onFirstPage(PlazaRepository.revision.value)
            // Only successful network first pages are persisted (an empty one clears the old
            // snapshot); appended pages never replace the slot.
            firstPageCache(own, account)?.let { store ->
                cacheWriteScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    PlazaRepository.persistFirstPage(store, page)
                }
            }
        }
        return FeedPage(page.items.map { PlazaPostItem(it) }, page.nextBefore)
    }
}

/**
 * What the feed needs beyond paging: the all / mine scope, the resume policy, one mutation at
 * a time with a busy marker, refreshes deferred while a mutation is in flight, and the
 * per-post media signature refresh. List contents live in the feeds ViewModel; this holds no
 * posts.
 */
internal class PlazaFeedController
@JvmOverloads
constructor(
    private val saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    firstPageCache: (Boolean, Long) -> FeedFirstPageStore<PlazaPage>? =
        PlazaRepository::firstPageCache,
    cacheWriteScope: CoroutineScope = feedCacheWriteScope,
) : ViewModel() {
    private fun requireAccount(expected: Long) {
        if (expected <= 0 || expected != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    val source: PlazaFeedSource =
        PlazaFeedSource(api, currentUid, { mine }, firstPageCache, cacheWriteScope, ::onFirstPage)

    var mine: Boolean
        get() = saved["mine"] ?: false
        private set(value) {
            saved["mine"] = value
        }

    /** Account the list currently belongs to; 0 until the first resume. */
    var account: Long = 0L
        private set

    private val busy = MutableStateFlow<Set<Long>>(emptySet())
    val busyIds = busy.asStateFlow()

    private val errors = MutableStateFlow<PlazaMessage?>(null)
    val error = errors.asStateFlow()
    private var presentedError: PlazaMessage? = null

    /** Refreshes the list should run now; deferred requests are replayed after a mutation. */
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshRequests = refreshes.asSharedFlow()
    private var pendingRefresh = false

    private var observedRevision = -1L
    private var loadedAt = 0L
    private val imageRefreshAt = mutableMapOf<Long, Long>()

    enum class Entry { NONE, REFRESH, SWITCH_SCOPE }

    fun takeErrorForAlert(): PlazaMessage? {
        val error = errors.value ?: return null
        if (error === presentedError) return null
        presentedError = error
        return error
    }

    private fun onFirstPage(revision: Long) {
        observedRevision = revision
        loadedAt = System.currentTimeMillis()
    }

    /**
     * Resume policy, as before the migration: a different account restarts the list from its
     * own snapshot; a revision moved elsewhere or four minutes on screen refresh in place.
     */
    fun enter(): Entry {
        val uid = currentUid()
        if (account == 0L) {
            // First resume: the feeds ViewModel auto-loaded for this account when it was created.
            account = uid
            observedRevision = PlazaRepository.revision.value
            loadedAt = System.currentTimeMillis()
            return Entry.NONE
        }
        if (uid != account) {
            account = uid
            observedRevision = -1
            loadedAt = 0
            imageRefreshAt.clear()
            return Entry.SWITCH_SCOPE
        }
        return if (
            observedRevision != PlazaRepository.revision.value ||
                System.currentTimeMillis() - loadedAt > 240_000
        ) Entry.REFRESH else Entry.NONE
    }

    /** @return true when the scope changed and the list must restart from that scope. */
    fun selectMine(value: Boolean): Boolean {
        if (mine == value) return false
        mine = value
        observedRevision = -1
        loadedAt = 0
        return true
    }

    /**
     * @return true when the caller should refresh the list now. While a mutation is in flight
     * the request is deferred instead and replayed through [refreshRequests] once it finishes,
     * so a stale page can never overwrite the mutation's result.
     */
    fun requestRefresh(): Boolean {
        observedRevision = PlazaRepository.revision.value
        if (busy.value.isEmpty()) return true
        pendingRefresh = true
        return false
    }

    fun like(post: PlazaPost) =
        mutate(post.id) { if (post.liked) api.unlike(post.id) else api.like(post.id) }

    fun react(post: PlazaPost, emoji: String) =
        mutate(post.id) {
            val stickerId =
                emoji.takeIf { it.startsWith("sticker:") }?.substringAfter(':')?.toLongOrNull()
            val selected = post.reactions.any { it.emoji == emoji && it.selected }
            if (stickerId != null) {
                if (selected) api.unreactSticker(post.id, stickerId)
                else api.reactSticker(post.id, stickerId)
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
        if (busy.value.isNotEmpty()) return
        busy.value = setOf(id)
        errors.value = null
        val account = currentUid()
        viewModelScope.launch {
            try {
                PlazaRepository.mutate {
                    requireAccount(account)
                    val updated = action()
                    requireAccount(account)
                    PlazaRepository.changed()
                    if (updated != null) PlazaRepository.cache(updated, account)
                    else PlazaRepository.invalidate(id, account)
                    observedRevision = PlazaRepository.revision.value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.value = plazaError(e)
            } finally {
                busy.value = busy.value - id
                if (pendingRefresh) {
                    pendingRefresh = false
                    refreshes.tryEmit(Unit)
                }
            }
        }
    }

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
        val requestRevision = PlazaRepository.revision.value
        val account = currentUid()
        viewModelScope.launch {
            try {
                requireAccount(account)
                val fresh = api.post(post.id)
                requireAccount(account)
                if (requestRevision != PlazaRepository.revision.value) return@launch
                PlazaRepository.cache(fresh, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 404 && account == currentUid() &&
                    requestRevision == PlazaRepository.revision.value) {
                    PlazaRepository.changed()
                    PlazaRepository.invalidate(post.id, account)
                }
                // The stale tile already shows its error placeholder; a pull refresh still works.
                Timber.d(e, "plaza: media signature refresh failed for post %d", post.id)
            }
        }
    }

    private companion object {
        /** Same margin as PlazaImageSource: a URL about to expire is treated as expired. */
        const val IMAGE_EXPIRY_MARGIN_MS = 5_000L
        const val IMAGE_REFRESH_INTERVAL_MS = 30_000L
    }
}
