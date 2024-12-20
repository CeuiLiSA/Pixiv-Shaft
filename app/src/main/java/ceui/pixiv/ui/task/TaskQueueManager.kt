package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import timber.log.Timber

object TaskQueueManager {

    // Define task states
    enum class TaskState {
        IDLE,       // Idle state
        RUNNING,    // Task is running
        PAUSED      // Task is paused
    }

    private val taskQueue: MutableList<DownloadTask> = mutableListOf()
    private val failedTasks: MutableList<DownloadTask> = mutableListOf()
    private val lock = Any() // Synchronization lock object

    // Use LiveData to observe task states
    private val _taskState = MutableLiveData(TaskState.IDLE)
    val taskState: LiveData<TaskState> get() = _taskState

    /**
     * Add a single task to the queue (avoiding duplicates)
     */
    fun addTask(task: DownloadTask) {
        synchronized(lock) {
            if (!isTaskInQueue(task)) {
                taskQueue.add(task)
                updateTaskState()
            } else {
                Timber.d("Task already in queue: ${task.taskId}")
            }
        }
    }

    /**
     * Add multiple tasks to the queue (avoiding duplicates)
     */
    fun addTasks(tasks: List<DownloadTask>) {
        synchronized(lock) {
            val newTasks = tasks.filterNot { isTaskInQueue(it) }
            if (newTasks.isNotEmpty()) {
                taskQueue.addAll(newTasks)
                updateTaskState()
            }
        }
    }

    /**
     * Check if a task already exists in the queue or failed task list
     */
    private fun isTaskInQueue(task: DownloadTask): Boolean {
        synchronized(lock) {
            return taskQueue.any { it.taskId == task.taskId } || failedTasks.any { it.taskId == task.taskId }
        }
    }

    /**
     * Start processing tasks
     */
    fun startProcessing() {
        synchronized(lock) {
            if (_taskState.value == TaskState.PAUSED || _taskState.value == TaskState.IDLE) {
                Timber.d("Starting task processing")
                _taskState.value = TaskState.RUNNING
            } else {
                Timber.d("Task processing cannot start. Current state: ${_taskState.value}")
                return
            }
        }
        processNextTask() // Call outside of lock to avoid long lock holding
    }

    /**
     * Pause task processing
     */
    fun pauseProcessing() {
        synchronized(lock) {
            if (_taskState.value == TaskState.RUNNING) {
                Timber.d("Pausing task processing")
                _taskState.value = TaskState.PAUSED
            } else {
                Timber.d("Cannot pause task processing. Current state: ${_taskState.value}")
            }
        }
    }

    /**
     * Process the next task
     */
    private fun processNextTask() {
        val currentTask: DownloadTask?
        synchronized(lock) {
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

            currentTask = taskQueue.firstOrNull {
                it.status.value is TaskStatus.NotStart || it.status.value is TaskStatus.Error
            }
        }

        currentTask?.startDownload(
            onSuccess = {
                Timber.d("Task succeeded: ${currentTask.content.url}")
                handleTaskCompletion()
            },
            onFailure = { exception ->
                Timber.e(exception, "Task failed: ${currentTask.content.url}")
                synchronized(lock) { failedTasks.add(currentTask) }
                handleTaskCompletion()
            }
        )
    }

    /**
     * Handle task completion logic
     */
    private fun handleTaskCompletion() {
        synchronized(lock) {
            if (taskQueue.isEmpty() && failedTasks.isEmpty()) {
                Timber.d("All tasks completed successfully")
                _taskState.value = TaskState.IDLE
            }
        }
        processNextTask()
    }

    /**
     * Retry failed tasks
     */
    private fun retryFailedTasks() {
        synchronized(lock) {
            Timber.d("Retrying failed tasks: ${failedTasks.size}")
            taskQueue.addAll(failedTasks)
            failedTasks.clear()
        }
        processNextTask()
    }

    /**
     * Clear all tasks
     */
    fun clearAllTasks() {
        synchronized(lock) {
            taskQueue.clear()
            failedTasks.clear()
            _taskState.value = TaskState.IDLE
        }
        Timber.d("All tasks cleared")
    }

    /**
     * Update task state
     */
    private fun updateTaskState() {
        synchronized(lock) {
            _taskState.value = if (taskQueue.isNotEmpty() || failedTasks.isNotEmpty()) {
                TaskState.IDLE
            } else {
                TaskState.IDLE
            }
        }
    }

    /**
     * Find an existing task by task ID
     */
    fun findExistingTask(taskId: Long): DownloadTask? {
        synchronized(lock) {
            val ret = taskQueue.firstOrNull { it.taskId == taskId } ?: failedTasks.firstOrNull { it.taskId == taskId }
            if (ret != null) {
                Timber.d("Task found: $taskId")
            } else {
                Timber.d("Task not found: $taskId")
            }
            return ret
        }
    }
}
