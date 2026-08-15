package ceui.pixiv.chat.base

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.chat.api.toAppError
import ceui.pixiv.chat.core.AppError

/**
 * Resolve a user-facing, localisable message for this [AppError].
 *
 * Errors with fixed semantics (network, auth, rate-limit, …) return a string
 * resource so the UI can be translated. Errors that carry a server-provided
 * message ([AppError.BadRequest], [AppError.Conflict], [AppError.Unprocessable],
 * [AppError.Server], [AppError.Unknown]) return that message directly, since it
 * contains context specific to the request.
 */
fun AppError.toUserMessage(context: Context): String = when (this) {
    is AppError.NetworkUnavailable -> context.getString(R.string.chat_error_network_unavailable)
    is AppError.RequestTimeout -> context.getString(R.string.chat_error_request_timeout)
    is AppError.SecurityError -> context.getString(R.string.chat_error_security)
    is AppError.Unauthorized -> context.getString(R.string.chat_error_unauthorized)
    is AppError.Forbidden -> context.getString(R.string.chat_error_forbidden)
    is AppError.NotFound -> context.getString(R.string.chat_error_not_found)
    is AppError.Gone -> context.getString(R.string.chat_error_gone)
    is AppError.RateLimited -> if (retryAfterSeconds != null) {
        context.getString(R.string.chat_error_rate_limited_with_delay, retryAfterSeconds)
    } else {
        context.getString(R.string.chat_error_rate_limited)
    }
    is AppError.ServiceUnavailable -> context.getString(R.string.chat_error_service_unavailable)
    is AppError.Serialization -> context.getString(R.string.chat_error_serialization)
    // Dynamic server messages — pass through since they carry request-specific detail.
    is AppError.BadRequest -> debugMessage
    is AppError.Conflict -> debugMessage
    is AppError.Unprocessable -> debugMessage
    is AppError.Server -> debugMessage
    is AppError.Unknown -> debugMessage
}

/**
 * One-shot resolution of a raw [Throwable] into a user-facing, localised message:
 * maps via [toAppError] then [toUserMessage]. Intended for legacy Java call sites
 * (e.g. `ErrorCtrl`) that only hold a [Throwable] and would otherwise surface a raw
 * `e.toString()` — or nothing at all — for network/timeout/serialization failures.
 */
fun Throwable.toUserMessage(context: Context): String =
    toAppError().toUserMessage(context)
