package ceui.pixiv.ui.task

import android.net.Uri
import android.provider.MediaStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.map
import ceui.pixiv.ui.common.deleteImageById
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import ceui.pixiv.widgets.alertYesOrCancel
import com.blankj.utilcode.util.UriUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
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

        val imageId = getImageIdInGallery(activity, content.name)
        if (imageId != null) {
            Timber.d("dfsasf3 ${content.name} 图片已存在")
            _status.value = TaskStatus.Finished
            onSuccessCallback?.invoke() // 成功回调
        } else {
            Timber.d("dfsasf3 ${content.name} 图片不已存在，准备下载")
            activity.lifecycleScope.launch {
                delay(1000L)
                execute()
            }
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