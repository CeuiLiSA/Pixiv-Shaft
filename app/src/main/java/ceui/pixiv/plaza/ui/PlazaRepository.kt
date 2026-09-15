package ceui.pixiv.plaza.ui

import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.plaza.*
import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException

/** Tokyo Bearer API. Revisions survive a stopped screen and force fresh signed image URLs. */
internal object PlazaRepository {
    val revision = MutableStateFlow(0L)
    val api
        get() = Client.plazaAPI

    fun changed() {
        revision.value += 1
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
