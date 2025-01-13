package ceui.pixiv.ui.task

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ceui.lisa.activities.Shaft
import timber.log.Timber
import java.util.UUID

abstract class QueuedRunnable<ResultT> {

    protected val context: Context
        get() {
            return Shaft.getContext()
        }

    protected val _status = MutableLiveData<TaskStatus>(TaskStatus.NotStart)
    val status: LiveData<TaskStatus> = _status

    protected val _result = MutableLiveData<ResultT>()
    val result: LiveData<ResultT> get() = _result

    open val taskId = UUID.randomUUID().hashCode().toLong()

    val isDownloading: LiveData<Boolean> = status.map { it is TaskStatus.Executing }

    abstract suspend fun execute()

    fun reset() {
        _status.value = TaskStatus.NotStart
    }

    open fun onIgnore() {
        Timber.d("${this.javaClass.simpleName}-${taskId} empty onIgnore ${_status.value}")
    }

    open fun onStart() {
        Timber.d("${this.javaClass.simpleName}-${taskId} onStart")
    }

    open fun onEnd(resultT: ResultT) {
        Timber.d("${this.javaClass.simpleName}-${taskId} onEnd")
    }

    open fun onError(ex: Exception?) {
        Timber.d("${this.javaClass.simpleName}-${taskId} handleError")
        if (ex != null) {
            Timber.e(ex)
            _status.postValue(TaskStatus.Error(ex))
        }
    }
}