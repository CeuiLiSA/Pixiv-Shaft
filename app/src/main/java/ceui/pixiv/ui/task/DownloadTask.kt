package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.pixiv.ui.common.saveImageToGallery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DownloadTask(content: NamedUrl, private val activity: FragmentActivity) :
    LoadTask(content, activity, autoStart = false) {

    private var onSuccessCallback: (() -> Unit)? = null
    private var onFailureCallback: ((Exception) -> Unit)? = null

    /**
     * 启动任务并设置回调
     */
    fun startDownload(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        onSuccessCallback = onSuccess
        onFailureCallback = onFailure

        activity.lifecycleScope.launch {
            delay(3000L)
            execute()
        }
    }

    override fun onFilePrepared(file: File) {
        super.onFilePrepared(file)
        saveImageToGallery(activity, file, content.name)
        onSuccessCallback?.invoke() // 成功回调
    }

    override fun handleError(ex: Exception?) {
        super.handleError(ex)
        if (ex != null) {
            onFailureCallback?.invoke(ex) // 错误回调
        }
    }
}