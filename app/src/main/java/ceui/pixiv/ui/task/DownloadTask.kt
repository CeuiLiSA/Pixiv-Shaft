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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class DownloadTask(content: NamedUrl, private val activity: FragmentActivity) :
    LoadTask(content, activity, autoStart = false) {

    private var _onNext: (() -> Unit)? = null

    init {
        Timber.d("fsaasdw2 创建了一个 DownloadTask: ${content}")
        activity.lifecycleScope.launch {
            val imageId = withContext(Dispatchers.IO) {
                getImageIdInGallery(activity, content.name)
            }
            if (imageId != null) {
                _status.value = TaskStatus.Finished
            }
        }
    }
    /**
     * 启动任务并设置回调
     */
    fun startDownload(onNext: () -> Unit) {
        this._onNext = onNext

        activity.lifecycleScope.launch {
            val imageId = withContext(Dispatchers.IO) {
                getImageIdInGallery(activity, content.name)
            }
            if (imageId != null) {
                Timber.d("dfsasf3 ${content.name} 图片已存在")
                delay(100L)
                _status.value = TaskStatus.Finished
                onNext.invoke()
            } else {
                Timber.d("dfsasf3 ${content.name} 图片不已存在，准备下载")
                delay(400L)
                execute()
            }
        }
    }

    override suspend fun onFilePrepared(file: File) {
        super.onFilePrepared(file)
        saveImageToGallery(activity, file, content.name)
        this._onNext?.invoke()
    }

    override fun handleError(ex: Exception?) {
        super.handleError(ex)
        if (ex != null) {
            this._onNext?.invoke()
        }
    }
}