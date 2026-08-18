package ceui.loxia

import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.utils.setOnClick
import retrofit2.HttpException
import timber.log.Timber
import java.io.Serializable
import java.lang.Exception

sealed class RefreshState: Serializable {
    data class LOADING(val title: String = "", val refreshHint: RefreshHint? = null) : RefreshState()
    data class FETCHING_LATEST(val hasContent: Boolean = true) : RefreshState()
    data class LOADED(val hasContent: Boolean = true, val hasNext: Boolean = true) : RefreshState()
    data class ERROR(val exception: Exception, val isInitialLoad: Boolean = false) : RefreshState()
}

fun ItemLoadingBinding.setUpHolderRefreshState(
    refreshState: LiveData<RefreshState>,
    viewLifecycleOwner: LifecycleOwner,
    retryBlock: () -> Unit,
) {
    val context = root.context
    emptyActionButton.setOnClick {
        retryBlock.invoke()
    }
    refreshState.observe(viewLifecycleOwner) { refreshState ->
        if (refreshState is RefreshState.LOADED) {
            progressCircular.hideProgress()
            loadingFrame.isVisible = false

            if (refreshState.hasContent) {
                emptyFrame.isVisible = false
            } else {
                emptyFrame.isVisible = true
                emptyActionButton.text = context.getString(R.string.refresh)
                emptyTitle.text = context.getString(R.string.empty_content_here)
            }
        } else if (refreshState is RefreshState.LOADING) {
            emptyFrame.isVisible = false
            if (refreshState.refreshHint == RefreshHint.PullToRefresh) {
                loadingFrame.isVisible = false
                progressCircular.hideProgress()
            } else {
                loadingFrame.isVisible = true
                progressCircular.showProgress()
            }
        } else if (refreshState is RefreshState.ERROR) {
            progressCircular.hideProgress()
            loadingFrame.isVisible = false

            emptyFrame.isVisible = true
            emptyActionButton.text = context.getString(R.string.retry)
            emptyTitle.text = refreshState.exception.getHumanReadableMessage(context)
        }
    }
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