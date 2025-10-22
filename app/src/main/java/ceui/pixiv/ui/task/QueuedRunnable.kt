package ceui.pixiv.ui.task

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.UUID

abstract class QueuedRunnable<ResultT> {

    protected val context: Context
        get() {
            return Shaft.getContext()
        }

    private var _onNext: (() -> Unit)? = null
    private val _impl = CompletableDeferred<ResultT>()

    protected val _status = MutableLiveData<TaskStatus>(TaskStatus.NotStart)
    val status: LiveData<TaskStatus> = _status

    protected val _result = MutableLiveData<ResultT>()
    val result: LiveData<ResultT> get() = _result

    open val taskId = UUID.randomUUID().hashCode().toLong()

    val isDownloading: LiveData<Boolean> = status.map { it is TaskStatus.Executing }

    open fun start(onNext: () -> Unit) {
        this._onNext = onNext
    }

    abstract suspend fun execute()

    suspend fun awaitResult(): ResultT {
        execute()
        return _impl.await()
    }

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
        this._onNext?.invoke()
        _impl.complete(resultT)
    }

    open fun cancel() {
        Timber.d("${this.javaClass.simpleName}-${taskId} cancel")
    }

    open fun onError(ex: Exception?) {
        Timber.d("${this.javaClass.simpleName}-${taskId} handleError")
        if (ex != null) {
            Timber.e(ex)
            _status.postValue(TaskStatus.Error(ex))
            this._onNext?.invoke()
            _impl.cancel()
        }
    }
}