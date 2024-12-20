package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import timber.log.Timber

object LoadTaskManager {

    // 定义任务状态
    enum class TaskState {
        IDLE,       // 空闲状态
        RUNNING,    // 正在运行
        PAUSED      // 暂停中
    }

    private val taskQueue: MutableList<DownloadTask> = mutableListOf()
    private val failedTasks: MutableList<DownloadTask> = mutableListOf()

    // 使用 LiveData 来观测状态
    private val _taskState = MutableLiveData(TaskState.IDLE)
    val taskState: LiveData<TaskState> get() = _taskState

    /**
     * 添加单个任务到队列（去重）
     */
    fun addTask(task: DownloadTask) {
        if (!isTaskInQueue(task)) {
            taskQueue.add(task)
            updateTaskState()
        } else {
            Timber.d("Task already in queue: ${task.taskId}")
        }
    }

    /**
     * 批量添加任务到队列（去重）
     */
    fun addTasks(tasks: List<DownloadTask>) {
        val newTasks = tasks.filterNot { isTaskInQueue(it) }
        if (newTasks.isNotEmpty()) {
            taskQueue.addAll(newTasks)
            updateTaskState()
        }
    }

    /**
     * 检查任务是否已经存在于队列或失败任务列表中
     */
    private fun isTaskInQueue(task: DownloadTask): Boolean {
        return taskQueue.any { it.taskId == task.taskId } || failedTasks.any { it.taskId == task.taskId }
    }

    /**
     * 开始任务
     */
    fun startProcessing() {
        if (_taskState.value == TaskState.PAUSED || _taskState.value == TaskState.IDLE) {
            Timber.d("Starting task processing")
            _taskState.value = TaskState.RUNNING
            processNextTask()
        } else {
            Timber.d("Task processing cannot start. Current state: ${_taskState.value}")
        }
    }

    /**
     * 暂停任务
     */
    fun pauseProcessing() {
        if (_taskState.value == TaskState.RUNNING) {
            Timber.d("Pausing task processing")
            _taskState.value = TaskState.PAUSED
        } else {
            Timber.d("Cannot pause task processing. Current state: ${_taskState.value}")
        }
    }

    /**
     * 处理下一个任务
     */
    private fun processNextTask() {
        if (_taskState.value != TaskState.RUNNING) {
            Timber.d("Task processing is not running. Current state: ${_taskState.value}")
            return
        }

        if (taskQueue.isEmpty()) {
            if (failedTasks.isNotEmpty()) {
                retryFailedTasks()
            } else {
                Timber.d("All tasks completed")
                _taskState.value = TaskState.IDLE
            }
            return
        }

        val currentTask = taskQueue.firstOrNull { it.status.value is TaskStatus.NotStart || it.status.value is TaskStatus.Error }
        currentTask?.startDownload(
            onSuccess = {
                Timber.d("Task succeeded: ${currentTask.content.url}")
                handleTaskCompletion()
            },
            onFailure = { exception ->
                Timber.e(exception, "Task failed: ${currentTask.content.url}")
                failedTasks.add(currentTask)
                handleTaskCompletion()
            }
        )
    }

    /**
     * 处理任务完成逻辑
     */
    private fun handleTaskCompletion() {
        if (taskQueue.isEmpty() && failedTasks.isEmpty()) {
            Timber.d("All tasks completed successfully")
            _taskState.value = TaskState.IDLE
        } else {
            processNextTask() // 继续处理下一个任务
        }
    }

    /**
     * 重新下载失败任务
     */
    private fun retryFailedTasks() {
        Timber.d("Retrying failed tasks: ${failedTasks.size}")
        taskQueue.addAll(failedTasks)
        failedTasks.clear()
        processNextTask()
    }

    /**
     * 清空所有任务
     */
    fun clearAllTasks() {
        taskQueue.clear()
        failedTasks.clear()
        _taskState.value = TaskState.IDLE
        Timber.d("All tasks cleared")
    }

    /**
     * 更新任务状态
     */
    private fun updateTaskState() {
        _taskState.value = if (taskQueue.isNotEmpty() || failedTasks.isNotEmpty()) {
            TaskState.IDLE
        } else {
            TaskState.IDLE
        }
    }

    fun findExistingTask(taskId: Long): DownloadTask? {
        val ret = taskQueue.firstOrNull { it.taskId == taskId } ?: failedTasks.firstOrNull { it.taskId == taskId }
        if (ret != null) {
            Timber.d("dfsdasw2 ret fount")
        } else {
            Timber.d("dfsdasw2 ret did not fount")
        }
        return ret
    }
}
