package ceui.pixiv.plaza.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.*
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.MediaObject
import ceui.pixiv.shaftapi.MediaUploadResume
import ceui.pixiv.shaftapi.MediaUploader
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val plazaReportReasons = listOf("child_safety", "sexual", "violence", "hate", "harassment", "privacy", "advertising", "spam", "illegal", "other")

internal data class ModerationState(
    val busy: Boolean = false,
    val error: PlazaMessage? = null,
    val done: Boolean = false,
    val receiptId: Long? = null,
    val receiptStatus: String = "pending",
    val blocks: List<PlazaBlockedUser> = emptyList(),
    val images: List<DraftImage> = emptyList(),
)

internal class PlazaModerationModel @JvmOverloads constructor(
    private val saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val safetyChanged: (Long) -> Unit = PlazaRepository::safetyChanged,
    private val uploader: suspend (
        ContentResolver, Uri, MediaUploadResume?, suspend (MediaUploadResume) -> Unit, (Int) -> Unit,
    ) -> MediaObject = MediaUploader::upload,
) : ViewModel() {
    private fun requireAccount() {
        if (owner <= 0 || owner != currentUid()) throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }
    val owner = saved.get<Long>("owner") ?: currentUid().also { saved["owner"] = it }
    val mode = saved.get<String>("mode") ?: "post"
    val state = MutableStateFlow(ModerationState(
        done = saved.get<Long>("receiptId") != null,
        receiptId = saved["receiptId"],
        receiptStatus = saved["receiptStatus"] ?: "pending",
        images = (saved.get<ArrayList<String>>("uris") ?: arrayListOf()).distinct().take(3).map { uri ->
            DraftImage(uri, saved["media:$uri"], if (saved.get<String>("media:$uri") != null) 100 else 0)
        },
    ))
    var reason: String
        get() = saved["reason"] ?: ""
        set(value) { if (!state.value.busy && !state.value.done) saved["reason"] = value }
    var details: String
        get() = saved["details"] ?: ""
        set(value) { if (!state.value.busy && !state.value.done) saved["details"] = value }

    fun attach(uris: List<Uri>) {
        if (state.value.busy || state.value.done) return
        val images = (state.value.images + uris.map { DraftImage(it.toString()) }).distinctBy { it.uri }.take(3)
        saved["uris"] = ArrayList(images.map { it.uri })
        state.value = state.value.copy(images = images)
    }

    fun removeImage(uri: String) {
        if (state.value.busy || state.value.done) return
        val images = state.value.images.filterNot { it.uri == uri }
        saved["uris"] = ArrayList(images.map { it.uri })
        saved.remove<String>("media:$uri")
        saved.remove<String>("resume:$uri")
        state.value = state.value.copy(images = images)
    }

    fun submit(unblockUid: Long? = null, resolver: ContentResolver? = null) {
        if (state.value.busy || state.value.done) return
        if (mode in listOf("post", "user") && reason !in plazaReportReasons) {
            state.value = state.value.copy(error = PlazaMessage(R.string.plaza_report_required))
            return
        }
        state.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                requireAccount()
                // Upload outside the shared mutation lock so photos do not block likes or blocking.
                if (mode == "post" || mode == "user") for (image in state.value.images) {
                    if (image.mediaId != null) continue
                    requireAccount()
                    val resume = saved.get<String>("resume:${image.uri}")?.let {
                        runCatching { Gson().fromJson(it, MediaUploadResume::class.java) }.getOrNull()
                    }
                    val media = uploader(checkNotNull(resolver), Uri.parse(image.uri), resume, { pending ->
                        withContext(Dispatchers.Main.immediate) {
                            requireAccount()
                            saved["resume:${image.uri}"] = Gson().toJson(pending)
                        }
                    }) { progress ->
                        state.update { s -> s.copy(images = s.images.map {
                            if (it.uri == image.uri) it.copy(progress = progress) else it
                        }) }
                    }
                    requireAccount()
                    saved["media:${image.uri}"] = media.id
                    saved.remove<String>("resume:${image.uri}")
                    state.update { s -> s.copy(images = s.images.map {
                        if (it.uri == image.uri) it.copy(mediaId = media.id, progress = 100) else it
                    }) }
                }
                PlazaRepository.mutate {
                    requireAccount()
                    when (mode) {
                        "blocks" -> {
                            if (unblockUid != null) {
                                try {
                                    check(api.unblock(unblockUid).ok)
                                } finally {
                                    // A lost response or account switch does not undo a server write.
                                    safetyChanged(owner)
                                }
                                requireAccount()
                            }
                            val users = api.blocks().items
                            requireAccount()
                            state.value = state.value.copy(blocks = users)
                        }
                        "block" -> {
                            val target = checkNotNull(saved.get<Long>("targetUid"))
                            try {
                                check(api.block(target).ok)
                            } finally {
                                safetyChanged(owner)
                            }
                            requireAccount()
                            state.value = state.value.copy(done = true)
                        }
                        else -> {
                            val receipt = api.report(checkNotNull(saved.get<Long>("postId")),
                                PlazaReportRequest(mode, reason, details.trim(), state.value.images.map { checkNotNull(it.mediaId) }))
                            requireAccount()
                            check(receipt.id > 0)
                            saved["receiptId"] = receipt.id
                            saved["receiptStatus"] = receipt.status
                            state.value = state.value.copy(done = true, receiptId = receipt.id, receiptStatus = receipt.status)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(error = plazaError(e))
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }
}
