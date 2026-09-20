package ceui.pixiv.plaza.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaBlockedUser
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlazaBlockedUserItem(val user: PlazaBlockedUser, val busy: Boolean = false) : FeedItem {
    override val feedKey: Any get() = user.uid
}

/** The feeds VM owns the list. This controller only owns account checks and mutations. */
internal class PlazaBlocksController @JvmOverloads constructor(
    saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val safetyChanged: (Long) -> Unit = PlazaRepository::safetyChanged,
) : ViewModel(), FeedSource<Unit> {
    val owner: Long = saved.get<Long>("owner") ?: currentUid().also { saved["owner"] = it }
    val error = MutableStateFlow<PlazaMessage?>(null)
    private var busy = false
    // A refresh must never put an old response back after a confirmed unblock.
    private val operations = Mutex()

    fun requireAccount() {
        if (owner <= 0 || owner != currentUid()) throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    override suspend fun load(cursor: Unit?): FeedPage<Unit> = operations.withLock {
        requireAccount()
        val users = api.blocks().items
        requireAccount()
        // The existing API returns the complete list, so feeds must not request another page.
        FeedPage(users.map { PlazaBlockedUserItem(it, busy) }, null)
    }

    fun unblock(uid: Long, feed: FeedViewModel<*>) {
        if (busy) return
        busy = true
        error.value = null
        feed.updateItems<PlazaBlockedUserItem> { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                operations.withLock {
                    PlazaRepository.mutate {
                        requireAccount()
                        try {
                            check(api.unblock(uid).ok)
                        } finally {
                            // A lost response or account switch does not undo a server write.
                            safetyChanged(owner)
                        }
                        requireAccount()
                        feed.mutateItems { items -> items.filterNot { it is PlazaBlockedUserItem && it.user.uid == uid } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = plazaError(e)
            } finally {
                busy = false
                feed.updateItems<PlazaBlockedUserItem> { if (it.busy) it.copy(busy = false) else it }
            }
        }
    }
}
