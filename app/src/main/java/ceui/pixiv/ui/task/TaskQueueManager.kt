package ceui.pixiv.ui.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import timber.log.Timber

object TaskQueueManager {

    enum class TaskState {
        IDLE,       // Idle state
        RUNNING,    // Task is running
        PAUSED      // Task is paused
    }

    private val taskQueue: MutableList<QueuedRunnable<*>> = mutableListOf()
    private val lock = Any()

    private val _taskState = MutableLiveData(TaskState.IDLE)
    val taskState: LiveData<TaskState> get() = _taskState

    fun addTask(task: QueuedRunnable<*>) {
        synchronized(lock) {
            if (!isTaskInQueue(task)) {
                taskQueue.add(task)
                Timber.d("Task added: ${task.taskId}")
                updateTaskState()
            } else {
                Timber.d("Task already in queue: ${task.taskId}")
            }
        }
    }

    fun addTasks(tasks: List<QueuedRunnable<*>>) {
        synchronized(lock) {
            val newTasks = tasks.filterNot { isTaskInQueue(it) }
            if (newTasks.isNotEmpty()) {
                taskQueue.addAll(newTasks)
                Timber.d("Tasks added: ${newTasks.map { it.taskId }}")
                updateTaskState()
            }
        }
    }

    private fun isTaskInQueue(task: QueuedRunnable<*>): Boolean {
        return taskQueue.any { it.taskId == task.taskId }
    }

    fun startProcessing() {
        synchronized(lock) {
            if (_taskState.value != TaskState.RUNNING) {
                Timber.d("Starting task processing")
                _taskState.value = TaskState.RUNNING
            } else {
                Timber.d("Task processing already running")
                return
            }
        }
        processNextTask()
    }

    fun pauseProcessing() {
        synchronized(lock) {
            if (_taskState.value == TaskState.RUNNING) {
                Timber.d("Pausing task processing")
                _taskState.value = TaskState.PAUSED
            } else {
                Timber.d("Cannot pause, current state: ${_taskState.value}")
            }
        }
    }

    private fun processNextTask() {
        val currentTask: QueuedRunnable<*>?
        synchronized(lock) {
            if (_taskState.value != TaskState.RUNNING) {
                Timber.d("Processing not running, current state: ${_taskState.value}")
                return
            }

            currentTask = taskQueue.firstOrNull { it.status.value is TaskStatus.NotStart }
            if (currentTask == null) {
                if (taskQueue.all { it.status.value is TaskStatus.Finished }) {
                    Timber.d("All tasks completed")
                    _taskState.value = TaskState.IDLE
                } else {
                    retryFailedTasks()
                }
                return
            }
        }

        currentTask?.start {
            handleTaskCompletion()
        }
    }

    private fun handleTaskCompletion() {
        synchronized(lock) {
            if (taskQueue.all { it.status.value is TaskStatus.Finished }) {
                Timber.d("All tasks completed")
                _taskState.value = TaskState.IDLE
            }
        }
        processNextTask()
    }

    /**
     * Retry failed tasks
     */
    private fun retryFailedTasks() {
        var shouldProcess = false
        synchronized(lock) {
            val failedTasks = taskQueue.filter { it.status.value is TaskStatus.Error }
            if (failedTasks.isNotEmpty()) {
                Timber.d("Retrying failed tasks: ${failedTasks.size}")
                failedTasks.forEach { task ->
                    task.reset()
                }
                shouldProcess = true
            } else {
                shouldProcess = false
                Timber.d("No failed tasks to retry")
            }
        }
        if (shouldProcess) {
            processNextTask()
        }
    }

    fun clearAllTasks() {
        synchronized(lock) {
            taskQueue.clear()
            _taskState.value = TaskState.IDLE
            Timber.d("All tasks cleared")
        }
    }

    private fun updateTaskState() {
        synchronized(lock) {
            _taskState.value = if (taskQueue.any { it.status.value !is TaskStatus.Finished }) {
                TaskState.IDLE
            } else {
                TaskState.IDLE
            }
        }
    }
}
