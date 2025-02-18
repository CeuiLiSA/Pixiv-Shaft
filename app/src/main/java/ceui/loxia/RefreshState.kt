package ceui.loxia

import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.pixiv.utils.setOnClick
import retrofit2.HttpException
import timber.log.Timber
import java.io.Serializable
import java.lang.Exception
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

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
    return if (this is SSLHandshakeException || this is TimeoutException || this is SocketTimeoutException) {
        "${context.getString(R.string.connection_error)}: ${this.javaClass.simpleName}"
    } else {
        val lc = localizedMessage
        if (lc == null) {
            context.getString(R.string.unknown_error_message)
        } else if (lc.contains("<html") || lc.contains("<!DOCTYPE html")) {
            val titleAfter = lc.substringAfter("<title>")
            val title = titleAfter.substringBefore("</title>")
            title
        } else {
            if (this is HttpException) {
                val errorBody = this.response()?.errorBody()?.string()
                try {
                    val obj = Shaft.sGson.fromJson(errorBody, ErrorResp::class.java)
                    obj.error?.user_message ?: errorBody ?: ""
                } catch (ex: kotlin.Exception) {
                    Timber.e(ex)
                    errorBody ?: ""
                }
            } else {
                "${lc}: ${this.javaClass.simpleName}"
            }
        }
    }
}