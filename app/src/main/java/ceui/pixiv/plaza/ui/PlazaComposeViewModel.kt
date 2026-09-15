package ceui.pixiv.plaza.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.CreatePost
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.MediaObject
import ceui.pixiv.shaftapi.MediaUploader
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DraftImage(val uri: String, val mediaId: String? = null, val progress: Int = 0)

internal data class ComposeState(
    val images: List<DraftImage> = emptyList(),
    val objectId: Long? = null,
    val objectType: String? = null,
    val sending: Boolean = false,
    val error: PlazaMessage? = null,
    val sentId: Long? = null,
)

internal class PlazaComposeViewModel
@JvmOverloads
constructor(
    private val saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val currentName: () -> String = { SessionManager.loggedInUser?.name.orEmpty() },
    private val uploader: suspend (ContentResolver, Uri, (Int) -> Unit) -> MediaObject =
        MediaUploader::upload,
) : ViewModel() {
    private fun requireAccount() {
        if (owner <= 0 || owner != currentUid())
            throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }

    private val mutable =
        MutableStateFlow(
            ComposeState(
                images =
                    (saved.get<ArrayList<String>>("uris") ?: arrayListOf()).map { uri ->
                        DraftImage(
                            uri,
                            saved.get<String>("media:$uri"),
                            if (saved.get<String>("media:$uri") != null) 100 else 0,
                        )
                    },
                objectId = saved["objectId"],
                objectType = saved["objectType"],
            )
        )
    val state = mutable.asStateFlow()
    private var presentedError: PlazaMessage? = null

    fun takeErrorForAlert(): PlazaMessage? {
        val error = state.value.error ?: return null
        if (error === presentedError) return null
        presentedError = error
        return error
    }

    var avatarUrl: String?
        get() = saved["avatarUrl"]
        set(value) {
            saved["avatarUrl"] = value
        }

    var title: String
        get() = saved["title"] ?: ""
        set(value) {
            if (!mutable.value.sending && title != value) {
                saved["title"] = value
                invalidateRequest()
            }
        }

    var text: String
        get() = saved["text"] ?: ""
        set(value) {
            if (!mutable.value.sending && text != value) {
                saved["text"] = value
                invalidateRequest()
            }
        }

    var replyTo: Long?
        get() = saved["replyTo"]
        set(value) {
            saved["replyTo"] = value
        }

    private val owner: Long = saved.get<Long>("owner") ?: currentUid().also { saved["owner"] = it }

    private fun invalidateRequest() {
        saved["requestId"] = UUID.randomUUID().toString()
    }

    fun attach(uris: List<Uri>) {
        if (mutable.value.sending) return
        val all =
            (mutable.value.images + uris.map { DraftImage(it.toString()) }).distinctBy { it.uri }
        mutable.value =
            mutable.value.copy(
                images = all.take(9),
                error =
                    if (all.size > 9) PlazaMessage(R.string.plaza_photo_limit, listOf(9)) else null,
            )
        saved["uris"] = ArrayList(mutable.value.images.map { it.uri })
        invalidateRequest()
    }

    fun remove(uri: String) {
        if (mutable.value.sending) return
        mutable.value =
            mutable.value.copy(images = mutable.value.images.filterNot { it.uri == uri })
        saved["uris"] = ArrayList(mutable.value.images.map { it.uri })
        invalidateRequest()
    }

    fun reference(id: Long?, type: String?) {
        if (mutable.value.sending) return
        require(id == null || (id > 0 && type in listOf("illust", "manga", "novel", "user")))
        saved["objectId"] = id
        saved["objectType"] = type
        mutable.value = mutable.value.copy(objectId = id, objectType = type)
        invalidateRequest()
    }

    fun canSend(): Boolean =
        !mutable.value.sending &&
            title.codePointCount(0, title.length) <= 120 &&
            text.codePointCount(0, text.length) <= 2000 &&
            (title.isNotBlank() ||
                text.isNotBlank() ||
                mutable.value.images.isNotEmpty() ||
                mutable.value.objectId != null)

    fun send(resolver: ContentResolver) {
        if (!canSend()) return
        mutable.value = mutable.value.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                requireAccount()
                // Sequential streaming bounds memory and avoids nine competing mobile uploads.
                // Successful media IDs survive retries and process recreation.
                for (image in mutable.value.images) {
                    if (image.mediaId != null) continue
                    val media =
                        uploader(resolver, Uri.parse(image.uri)) { percent ->
                            mutable.update { s ->
                                s.copy(
                                    images =
                                        s.images.map {
                                            if (it.uri == image.uri) it.copy(progress = percent)
                                            else it
                                        }
                                )
                            }
                        }
                    requireAccount()
                    saved["media:${image.uri}"] = media.id
                    mutable.update { s ->
                        s.copy(
                            images =
                                s.images.map {
                                    if (it.uri == image.uri)
                                        it.copy(mediaId = media.id, progress = 100)
                                    else it
                                }
                        )
                    }
                }
                requireAccount()
                val requestId =
                    saved.get<String>("requestId")
                        ?: UUID.randomUUID().toString().also { saved["requestId"] = it }
                val s = mutable.value
                val post =
                    api.create(
                        CreatePost(
                            requestId,
                            text.trim(),
                            currentName(),
                            s.images.map { checkNotNull(it.mediaId) },
                            s.objectId,
                            s.objectType,
                            replyTo,
                            title.trim(),
                            avatarUrl,
                        )
                    )
                requireAccount()
                PlazaRepository.changed()
                mutable.value = mutable.value.copy(sentId = post.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = plazaError(e))
            } finally {
                mutable.value = mutable.value.copy(sending = false)
            }
        }
    }
}
