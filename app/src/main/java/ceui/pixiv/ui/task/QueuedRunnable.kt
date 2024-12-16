package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

abstract class QueuedRunnable {

    protected val _status = MutableLiveData<TaskStatus>(TaskStatus.NotStart)
    val status: LiveData<TaskStatus> = _status

    abstract suspend fun execute()
}