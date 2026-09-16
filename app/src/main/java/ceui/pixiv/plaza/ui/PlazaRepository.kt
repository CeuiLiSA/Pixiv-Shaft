package ceui.pixiv.plaza.ui

import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.cache.feedFirstPageCache
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import ceui.pixiv.plaza.*
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** Tokyo Bearer API. Revisions survive a stopped screen and force fresh signed image URLs. */
internal object PlazaRepository {
    private val mutationMutex = Mutex()
    private val snapshotWriteMutex = Mutex()
    val revision = MutableStateFlow(0L)
    val api
        get() = Client.plazaAPI

    fun changed() {
        revision.value += 1
    }

    /** Full mutation responses must be applied in order across list/detail ViewModels. */
    suspend fun <T> mutate(action: suspend () -> T): T = mutationMutex.withLock { action() }

    /** Launched undispatched in feedCacheWriteScope so accepted first pages are saved in order. */
    suspend fun persistFirstPage(store: FeedFirstPageStore<PlazaPage>, page: PlazaPage) {
        snapshotWriteMutex.withLock { store.write(page, page.nextBefore?.toString()) }
    }

    /** Reuse the shared Room first-page snapshots, pinned to the request's account. */
    fun firstPageCache(mine: Boolean, viewerUid: Long) = feedFirstPageCache(
        slot = if (mine) "plaza-mine" else "plaza-all",
        type = PlazaPage::class.java,
        accountId = { viewerUid },
    )

    /** Gson can populate non-null Kotlin fields with null. Validate the entire disk page
     * before publishing anything to observers; a rejected snapshot must not poison the pool.
     */
    fun restorePage(page: PlazaPage, viewerUid: Long, savedAt: Long): List<PlazaPost> {
        requireNotNull(page.items).forEach { post ->
            requireNotNull(post)
            require(post.id > 0 && post.uid > 0)
            requireNotNull(post.displayName)
            requireNotNull(post.text)
            requireNotNull(post.title)
            requireNotNull(post.images).forEach { image ->
                requireNotNull(image)
                requireNotNull(image.mediaId)
                requireNotNull(image.contentType)
                requireNotNull(image.url)
            }
            requireNotNull(post.reactions).forEach { reaction ->
                requireNotNull(reaction)
                requireNotNull(reaction.emoji)
            }
            requireNotNull(post.commentsPreview).forEach { preview ->
                requireNotNull(preview)
                requireNotNull(preview.displayName)
                requireNotNull(preview.text)
            }
        }
        return page.items.mapNotNull { restore(it, viewerUid, savedAt) }
    }

    /** Disk snapshots may seed missing entries but never replace newer live state or tombstones. */
    fun restore(post: PlazaPost, viewerUid: Long, savedAt: Long): PlazaPost? {
        cachedEntry(post.id, viewerUid)?.let { return it.post }
        ObjectPool.update(
            PlazaPostCacheEntry(post.id, viewerUid, post, savedAt, revision = -1),
            isFullVersion = true,
        )
        return post
    }

    fun cachedEntry(id: Long, viewerUid: Long): PlazaPostCacheEntry? =
        ObjectPool.get<PlazaPostCacheEntry>(id).value
            ?.takeIf { viewerUid > 0 && it.viewerUid == viewerUid }

    fun hasFreshPost(id: Long, viewerUid: Long): Boolean {
        val entry = cachedEntry(id, viewerUid) ?: return false
        val post = entry.post ?: return false
        val now = System.currentTimeMillis()
        return entry.revision == revision.value && now - entry.cachedAt < 240_000 &&
            post.images.all { it.expiresAt > now + 5_000 }
    }

    /** Main thread; replace complete responses, including empty reactions and previews. */
    fun cache(post: PlazaPost, viewerUid: Long) {
        ObjectPool.update(
            PlazaPostCacheEntry(post.id, viewerUid, post, System.currentTimeMillis(), revision.value),
            isFullVersion = true,
        )
    }

    fun invalidate(id: Long, viewerUid: Long) {
        ObjectPool.update(
            PlazaPostCacheEntry(id, viewerUid, null, System.currentTimeMillis(), revision.value),
            isFullVersion = true,
        )
    }

    fun requireAccount(uid: Long) {
        if (uid <= 0 || uid != SessionManager.loggedInUid)
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }
}

internal fun plazaError(error: Exception): PlazaMessage =
    when (error) {
        is PlazaFailure -> error.userMessage
        is ceui.pixiv.shaftapi.MediaUploadException -> {
            val id =
                when (error.reason) {
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.TYPE ->
                        R.string.plaza_media_type_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.SIZE ->
                        R.string.plaza_media_size_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.SIZE_UNKNOWN ->
                        R.string.plaza_media_size_unknown
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.READ ->
                        R.string.plaza_media_read_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.BOUNDS ->
                        R.string.plaza_media_bounds_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.METHOD ->
                        R.string.plaza_media_method_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.CHANGED ->
                        R.string.plaza_media_changed_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.HTTP ->
                        R.string.plaza_media_http_error
                    ceui.pixiv.shaftapi.MediaUploadException.Reason.DIMENSIONS ->
                        R.string.plaza_media_dimensions_error
                }
            PlazaMessage(id, error.httpStatus?.let { listOf(it) } ?: emptyList())
        }
        is HttpException ->
            when (error.code()) {
                401 -> PlazaMessage(R.string.plaza_auth_error)
                404 -> PlazaMessage(R.string.plaza_not_found)
                409 -> PlazaMessage(R.string.plaza_conflict_error)
                429 -> PlazaMessage(R.string.plaza_rate_error)
                413 -> PlazaMessage(R.string.plaza_size_error)
                else -> PlazaMessage(R.string.plaza_http_error, listOf(error.code()))
            }
        is java.io.IOException -> PlazaMessage(R.string.plaza_network_error)
        else -> PlazaMessage(R.string.plaza_generic_error)
    }
