package ceui.pixiv.ui.artlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.withMirroredBookmarks
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class ArtlistCardItem(val list: Artlist) : FeedItem { override val feedKey: Any = list.id }
internal data class ArtlistHeaderItem(val list: Artlist) : FeedItem { override val feedKey: Any = "artlist_header" }
internal data class ArtlistNotice(val message: String? = null, val finish: Boolean = false)
internal data class ArtlistBatchProgress(val completed: Int, val total: Int, val name: String, val error: String? = null)

/** Survives rotation; requests capture the initiating uid, never a Fragment or a view. */
internal class ArtlistModel(private val saved: SavedStateHandle) : ViewModel() {
    val listId: Long = saved[ArtlistNavigation.ID] ?: 0L
    val work: Illust? = saved.get<String>(ArtlistNavigation.WORK)?.let { Gson().fromJson(it, Illust::class.java) }
    private val workIds: LongArray? = saved[ArtlistNavigation.WORK_IDS]
    val picking: Boolean get() = work != null || workIds != null
    val batchPicking: Boolean get() = workIds != null
    var scope: String
        get() = if (picking) "mine" else saved[ArtlistNavigation.SCOPE] ?: "all"
        set(value) { saved[ArtlistNavigation.SCOPE] = value }
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<ArtlistNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableBatchProgress = MutableStateFlow(workIds?.let { ids ->
        saved.get<String>("batch_name")?.let { name ->
            ArtlistBatchProgress(saved["batch_completed"] ?: 0, ids.size, name)
        }
    })
    val batchProgress = mutableBatchProgress.asStateFlow()
    // The feed survives configuration changes, so its invalidation watermark must survive too.
    private var seenRevision = ArtlistRepository.revision.value
    fun consumeRevisionChange(revision: Long): Boolean {
        if (seenRevision == revision) return false
        seenRevision = revision
        return true
    }
    var account = saved.get<Long>("artlist_account") ?: SessionManager.loggedInUid
        private set

    init { saved["artlist_account"] = account }

    fun accountChanged(): Boolean {
        val next = SessionManager.loggedInUid
        if (next == account) return false
        // A picker selection belongs to its initiating account, including after restoration.
        if (!picking) {
            account = next
            saved["artlist_account"] = next
        }
        return true
    }

    fun addTo(list: Artlist) {
        if (mutableBusy.value) return
        val ids = workIds
        if (ids == null) {
            val selected = work ?: return
            mutate(finish = true) { uid ->
                val updated = ArtlistRepository.add(uid, list.id, selected)
                Shaft.getContext().getString(R.string.artlist_add_done, updated.name)
            }
            return
        }
        if (saved.get<Long>("batch_target") != list.id) {
            saved["batch_target"] = list.id
            saved["batch_completed"] = 0
        }
        saved["batch_name"] = list.name
        var completed = saved.get<Int>("batch_completed") ?: 0
        mutableBatchProgress.value = ArtlistBatchProgress(completed, ids.size, list.name)
        mutate(finish = true) { uid ->
            try {
                val start = completed
                for (index in start until ids.size) {
                    // The existing artlist API permits 60 writes per account per minute.
                    if (index > start) delay(1100)
                    ArtlistRepository.add(uid, list.id, Illust(id = ids[index]))
                    completed = index + 1
                    saved["batch_completed"] = completed
                    mutableBatchProgress.value = ArtlistBatchProgress(completed, ids.size, list.name)
                }
                Shaft.getContext().getString(R.string.artlist_add_done, list.name)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableBatchProgress.value = ArtlistBatchProgress(completed, ids.size, list.name,
                    e.message ?: Shaft.getContext().getString(R.string.artlist_network_error))
                throw e
            }
        }
    }

    val lists = FeedSource<String> { cursor ->
        val uid = SessionManager.loggedInUid
        val requestedScope = scope
        val page = ArtlistRepository.lists(uid, requestedScope, cursor?.toLongOrNull(), work?.id)
        if (scope != requestedScope) throw CancellationException("Artlist scope changed")
        val items = withContext(Dispatchers.Default) {
            page.items.map { it.withFilteredCover() }.map(::ArtlistCardItem)
        }
        ArtlistRepository.checkAccount(uid)
        FeedPage(items, page.nextBefore?.toString())
    }

    val works = FeedSource<String> { cursor ->
        val uid = SessionManager.loggedInUid
        var revision: Long
        var detail: Artlist? = null
        var page: ceui.lisa.model.ListIllust
        do {
            revision = ArtlistRepository.revision.value
            if (cursor == null) detail = ArtlistRepository.detail(uid, listId)
            page = ArtlistRepository.works(uid, listId, cursor)
        } while (revision != ArtlistRepository.revision.value)
        val items = withContext(Dispatchers.Default) {
            page.illusts.orEmpty().mapNotNull { IllustFeedItem.of(it) }
        }.withMirroredBookmarks()
        val header = detail?.let { raw ->
            val filtered = withContext(Dispatchers.Default) { raw.withFilteredCover() }
            listOf(ArtlistHeaderItem(filtered))
        }.orEmpty()
        ArtlistRepository.checkAccount(uid)
        FeedPage(header + items, page.nextUrl)
    }

    fun mutate(finish: Boolean = false, block: suspend (Long) -> String?) {
        if (mutableBusy.value) return
        val uid = account
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val message = block(uid)
                ArtlistRepository.checkAccount(uid)
                mutableNotice.value = ArtlistNotice(message, finish)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableNotice.value = ArtlistNotice(e.message ?: Shaft.getContext().getString(R.string.artlist_network_error)) }
            finally { mutableBusy.value = false }
        }
    }
    fun consumeNotice() { mutableNotice.value = null }
}

private fun Artlist.withFilteredCover(): Artlist = copy(cover = cover?.takeIf {
    IllustFeedItem.of(it) != null
})

internal data class ArtlistEditorState(val loading: Boolean = false, val saving: Boolean = false,
    val error: String? = null, val completed: Artlist? = null)

internal class ArtlistEditorModel(private val saved: SavedStateHandle) : ViewModel() {
    private val uid: Long = saved.get<Long>("editor_uid") ?: SessionManager.loggedInUid.also { saved["editor_uid"] = it }
    val listId: Long = saved[ArtlistNavigation.ID] ?: 0L
    private val work = saved.get<String>(ArtlistNavigation.WORK)?.let { Gson().fromJson(it, Illust::class.java) }
    var name: String
        get() = saved["draft_name"] ?: ""
        set(value) { saved["draft_name"] = value }
    var description: String
        get() = saved["draft_description"] ?: ""
        set(value) { saved["draft_description"] = value }
    private val mutable = MutableStateFlow(ArtlistEditorState())
    val state = mutable.asStateFlow()
    init { if (listId > 0 && saved.get<Boolean>("draft_loaded") != true) load() }

    fun load() {
        if (mutable.value.loading) return
        mutable.value = ArtlistEditorState(loading = true)
        viewModelScope.launch {
            try {
                val item = ArtlistRepository.detail(uid, listId)
                if (item.uid != uid) throw IllegalStateException(Shaft.getContext().getString(R.string.artlist_forbidden))
                name = item.name; description = item.description; saved["draft_loaded"] = true
                mutable.value = ArtlistEditorState()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = ArtlistEditorState(error = e.message) }
        }
    }

    fun save() {
        if (mutable.value.saving || mutable.value.loading) return
        if (listId > 0 && saved.get<Boolean>("draft_loaded") != true) { load(); return }
        val title = name.trim()
        if (title.isEmpty() || title.codePointCount(0, title.length) > 60) {
            mutable.value = ArtlistEditorState(error = Shaft.getContext().getString(R.string.artlist_name_required)); return
        }
        val body = description.trim()
        if (body.codePointCount(0, body.length) > 1000) {
            mutable.value = ArtlistEditorState(error = Shaft.getContext().getString(R.string.artlist_description_limit)); return
        }
        val owner = SessionManager.loggedInAccount.value?.user?.name.orEmpty()
        val requestId = saved.get<String>("request_id") ?: UUID.randomUUID().toString().also { saved["request_id"] = it }
        mutable.value = ArtlistEditorState(saving = true)
        viewModelScope.launch {
            try {
                val existingId = if (listId > 0) listId else saved.get<Long>("created_list_id") ?: 0L
                var item = if (existingId > 0) ArtlistRepository.edit(uid, existingId, ArtlistEdit(title, body))
                    else ArtlistRepository.create(uid, ArtlistCreate(title, body, owner, requestId))
                if (listId == 0L) {
                    saved["created_list_id"] = item.id
                    // A lost create response can be replayed after the user has amended the draft.
                    if (item.name != title || item.description != body)
                        item = ArtlistRepository.edit(uid, item.id, ArtlistEdit(title, body))
                }
                if (work != null) item = ArtlistRepository.add(uid, item.id, work)
                mutable.value = ArtlistEditorState(completed = item)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = ArtlistEditorState(error = e.message) }
        }
    }
    fun consumeResult() { mutable.value = mutable.value.copy(completed = null) }
}
