package ceui.pixiv.ui.common

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.http.CfBlockDetector
import ceui.lisa.http.CfBlockGuide
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
    // 正文只读一次，CF 分类与后面的错误解析共用这一份 —— errorBody().string() 是一次性的，
    // 谁再读一遍都只会拿到空。
    val errorBody = readErrorBodyOnce()

    // Cloudflare 拦截排在最前。那类 403 的正文是一整张 374 KB 的 HTML 拦截页，顺着下面
    // 任何一条分支走都会把「一坨 HTML」当文案交出去。判定见 CfBlockDetector。
    //
    // 正文是**传进去**的，不是让分类器自己去 raw 响应上嗅：Retrofit 会把 raw 的 body
    // 换成空的 NoContentResponseBody，正文只存在于 errorBody() 里（见 CfBlockDetector 类注释）。
    val cfRaw = CfBlockDetector.rawResponseOf(this)
    if (cfRaw != null && CfBlockDetector.isCfBlock(cfRaw.code, cfRaw.headers, errorBody)) {
        // 引导弹窗挂在这个接缝上，而不是只挂在 ErrorCtrl 的 toast 那条路：本函数有 4 个
        // 调用点（信息流加载失败、详情页区块失败、账号切换失败、UIAction 确认框），
        // 它们都不经过 ErrorCtrl —— 只在那边触发的话，用户从信息流撞上 CF 拦截就永远
        // 看不到引导。配额由 CfBlockGuide 内部把关，所以多调用点不会变成多次弹窗。
        CfBlockGuide.maybeGuide(cfRaw)
        return context.getString(CfBlockGuide.shortMessage(CfBlockGuide.isProxyMode(cfRaw)))
    }

    val lc = localizedMessage
    // 服务器直接返回 HTML 错误页(网关 / Cloudflare 之类)→ 取 <title> 当提示
    if (lc != null && (lc.contains("<html") || lc.contains("<!DOCTYPE html"))) {
        return lc.substringAfter("<title>").substringBefore("</title>")
    }
    // HttpException 优先取服务端 error body 里的 user_message(比按状态码套的通用文案精确)
    if (this is HttpException) {
        val serverMsg = try {
            Shaft.sGson.fromJson(errorBody, ErrorResp::class.java)?.error?.user_message
        } catch (ex: kotlin.Exception) {
            Timber.e(ex)
            null
        }
        if (!serverMsg.isNullOrBlank()) return serverMsg
        // 正文不是可读文案时**不能原样抛**：HTML 错误页(网关 / Cloudflare / 源站自己的
        // 404 页)会变成一坨标签糊在 UI 上。只有 JSON 错误体才值得透传。
        if (!errorBody.isNullOrBlank() && !looksLikeHtml(errorBody)) return errorBody
        // 服务端没给可读文案 → 落到下面按状态码取本地化文案
    }
    // 网络中断 / 超时 / SSL / 反序列化 / 未知:统一映射成 AppError 再取本地化文案,
    // 取代原先 "Connection Error: SocketTimeoutException"、"xxx: SimpleName" 这类露原始异常类名的写法。
    return toUserMessage(context)
}

/**
 * 把 `errorBody()` 读成字符串。**整个流程只读这一次。**
 *
 * 失败一律返回 null（等价于「没有正文」）：分类退到头通路，业务解析退到通用文案。
 */
private fun Throwable.readErrorBodyOnce(): String? {
    val http = this as? HttpException ?: return null
    return try {
        http.response()?.errorBody()?.string()
    } catch (ex: kotlin.Exception) {
        Timber.e(ex)
        null
    }
}

/**
 * 粗略判断一段正文是不是 HTML 文档。
 *
 * 用来拦住「把错误页标签当文案返回」——JSON 正文永远不以 `<` 开头，所以这个判断对
 * 正常错误体零误伤。
 */
private fun looksLikeHtml(body: String): Boolean {
    val head = body.trimStart()
    return head.startsWith("<") ||
        head.contains("<html", ignoreCase = true) ||
        head.contains("<!DOCTYPE", ignoreCase = true)
}