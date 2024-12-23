package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import timber.log.Timber
import java.util.UUID

abstract class QueuedRunnable {

    protected val _status = MutableLiveData<TaskStatus>(TaskStatus.NotStart)
    val status: LiveData<TaskStatus> = _status

    open val taskId = UUID.randomUUID().hashCode().toLong()

    val isDownloading: LiveData<Boolean> = status.map { it is TaskStatus.Executing }

    abstract suspend fun execute()

    fun reset() {
        _status.value = TaskStatus.NotStart
    }

    open fun onIgnore() {
        Timber.d("${this.javaClass.simpleName}-${taskId} empty onIgnore")
    }

    open fun onStart() {
        Timber.d("${this.javaClass.simpleName}-${taskId} onStart")
    }

    open fun onEnd() {
        Timber.d("${this.javaClass.simpleName}-${taskId} onEnd")
    }

    open fun handleError(ex: Exception?) {
        Timber.d("${this.javaClass.simpleName}-${taskId} handleError")
        if (ex != null) {
            Timber.e(ex)
            _status.postValue(TaskStatus.Error(ex))
        }
    }
}