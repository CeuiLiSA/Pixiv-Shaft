package ceui.pixiv.ui.common

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.ErrorResp
import ceui.pixiv.chat.base.toUserMessage
import retrofit2.HttpException
import timber.log.Timber
import java.io.Serializable
import java.lang.Exception

sealed class RefreshState: Serializable {
    data class LOADING(val title: String = "", val refreshHint: RefreshHint? = null) : RefreshState()
    data class LOADED(val hasContent: Boolean = true, val hasNext: Boolean = true) : RefreshState()
    data class ERROR(val exception: Exception, val isInitialLoad: Boolean = false) : RefreshState()
}

fun Throwable.getHumanReadableMessage(context: Context): String {
    val lc = localizedMessage
    // 服务器直接返回 HTML 错误页(网关 / Cloudflare 之类)→ 取 <title> 当提示
    if (lc != null && (lc.contains("<html") || lc.contains("<!DOCTYPE html"))) {
        return lc.substringAfter("<title>").substringBefore("</title>")
    }
    // HttpException 优先取服务端 error body 里的 user_message(比按状态码套的通用文案精确)
    if (this is HttpException) {
        val errorBody = this.response()?.errorBody()?.string()
        val serverMsg = try {
            Shaft.sGson.fromJson(errorBody, ErrorResp::class.java)?.error?.user_message
        } catch (ex: kotlin.Exception) {
            Timber.e(ex)
            null
        }
        if (!serverMsg.isNullOrBlank()) return serverMsg
        if (!errorBody.isNullOrBlank()) return errorBody
        // 服务端没给可读文案 → 落到下面按状态码取本地化文案
    }
    // 网络中断 / 超时 / SSL / 反序列化 / 未知:统一映射成 AppError 再取本地化文案,
    // 取代原先 "Connection Error: SocketTimeoutException"、"xxx: SimpleName" 这类露原始异常类名的写法。
    return toUserMessage(context)
}