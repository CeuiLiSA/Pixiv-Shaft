package ceui.pixiv.ui.task

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import timber.log.Timber
import java.io.File

open class LoadTask(
    val content: NamedUrl,
    private val activity: FragmentActivity,
    autoStart: Boolean = true
) : QueuedRunnable() {

    private val _file = MutableLiveData<File>()
    val file: LiveData<File> get() = _file

    init {
        if (autoStart) {
            activity.lifecycleScope.launch {
                execute()
            }
        }
    }

    override suspend fun execute() {
        // 防止重复执行任务
        if (_status.value !is TaskStatus.NotStart) return

        try {
            _status.value = TaskStatus.Executing(0)
            observeProgress()

            val file = downloadFile()
            if (file != null) {
                _file.value = file
                onFilePrepared(file)
                _status.value = TaskStatus.Finished
            } else {
                throw IllegalStateException("Unexpected null file")
            }
        } catch (ex: Exception) {
            handleError(ex)
        }
    }

    private fun observeProgress() {
        ProgressManager.getInstance().addResponseListener(content.url, object : ProgressListener {
            override fun onProgress(progressInfo: ProgressInfo) {
                val percent = progressInfo.percent
                if (progressInfo.isFinish || percent == 100) {
                    _status.value = TaskStatus.Finished
                } else {
                    _status.value = TaskStatus.Executing(percent)
                }
            }

            override fun onError(id: Long, ex: Exception) {
                handleError(ex)
            }
        })
    }

    private suspend fun downloadFile(): File? {
        return withContext(Dispatchers.IO) {
            Glide.with(activity)
                .asFile()
                .load(
                    content.url.takeIf { it.startsWith("http") }
                        ?.let { GlideUrlChild(it) }
                        ?: Uri.parse(content.url)
                )
                .listener(createGlideListener())
                .submit()
                .get()
        }
    }

    private fun createGlideListener(): RequestListener<File> {
        return object : RequestListener<File> {
            override fun onLoadFailed(
                ex: GlideException?,
                model: Any?,
                target: com.bumptech.glide.request.target.Target<File>,
                isFirstResource: Boolean
            ): Boolean {
                handleError(ex)
                return false
            }

            override fun onResourceReady(
                resource: File,
                model: Any,
                target: Target<File>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                Common.showLog("Resource ready: ${dataSource.name}, path: ${resource.path}")
                return false
            }
        }
    }

    open fun handleError(ex: Exception?) {
        if (ex != null) {
            Timber.e(ex)
            _status.postValue(TaskStatus.Error(ex))
        }
    }

    open fun onFilePrepared(file: File) {
        // 子类实现
    }
}