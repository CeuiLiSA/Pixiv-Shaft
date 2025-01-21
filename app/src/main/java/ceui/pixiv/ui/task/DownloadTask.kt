package ceui.pixiv.ui.task

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.common.getImageIdInGallery
import ceui.pixiv.ui.common.saveImageToGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.invoke

class DownloadTask(
    content: NamedUrl,
    private val coroutineScope: CoroutineScope
) : LoadTask(content, coroutineScope, autoStart = false) {

    init {
        Timber.d("fsaasdw2 创建了一个 DownloadTask: ${content}")
        coroutineScope.launch {
            val imageId = withContext(Dispatchers.IO) {
                getImageIdInGallery(context, content.name)
            }
            if (imageId != null) {
                _status.value = TaskStatus.Finished
            }
        }
    }

    /**
     * 启动任务并设置回调
     */
    override fun start(onNext: () -> Unit) {
        super.start(onNext)

        coroutineScope.launch {
            val imageId = withContext(Dispatchers.IO) {
                getImageIdInGallery(context, content.name)
            }
            if (imageId != null) {
                Timber.d("${content.name} 图片已存在")
                delay(100L)
                _status.value = TaskStatus.Finished
                onNext.invoke()
            } else {
                Timber.d("${content.name} 图片不已存在，准备下载")
                delay(400L)
                execute()
            }
        }
    }

    override fun onEnd(resultT: File) {
        saveImageToGallery(context, resultT, content.name)
        super.onEnd(resultT)
    }
}