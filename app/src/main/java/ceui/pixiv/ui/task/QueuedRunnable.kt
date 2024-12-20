package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
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
}