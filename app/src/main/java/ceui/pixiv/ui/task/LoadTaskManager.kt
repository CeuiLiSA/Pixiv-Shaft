package ceui.pixiv.ui.task

import timber.log.Timber

object LoadTaskManager {

    private val taskQueue: MutableList<DownloadTask> = mutableListOf()
    private val failedTasks: MutableList<DownloadTask> = mutableListOf()
    private var isRunning: Boolean = false

    /**
     * 添加单个任务到队列（去重）
     */
    fun addTask(task: DownloadTask) {
        if (!isTaskInQueue(task)) {
            taskQueue.add(task)
            processNextTask()
        } else {
            Timber.d("Task already in queue: ${task.taskId}")
        }
    }

    /**
     * 批量添加任务到队列（去重）
     */
    fun addTasks(tasks: List<DownloadTask>) {
        val newTasks = tasks.filterNot { isTaskInQueue(it) } // 过滤掉已存在的任务
        if (newTasks.isNotEmpty()) {
            taskQueue.addAll(newTasks)
            processNextTask()
        }
    }

    /**
     * 检查任务是否已经存在于队列或失败任务列表中
     */
    private fun isTaskInQueue(task: DownloadTask): Boolean {
        return taskQueue.any { it.taskId == task.taskId } || failedTasks.any { it.taskId == task.taskId }
    }

    /**
     * 处理下一个任务
     */
    private fun processNextTask() {
        // 如果当前有任务正在运行，则直接返回
        if (isRunning) return

        // 如果任务队列为空，则检查是否有失败任务需要重试
        if (taskQueue.isEmpty()) {
            if (failedTasks.isNotEmpty()) {
                retryFailedTasks()
            }
            return
        }

        // 开始处理任务
        isRunning = true
        Timber.d("processNextTask:  ${getTaskStatus()}")
        val currentTask = taskQueue.removeAt(0)

        currentTask.startDownload(
            onSuccess = {
                Timber.d("Task succeeded: ${currentTask.content.url}")
                handleTaskCompletion() // 处理任务完成逻辑
            },
            onFailure = { exception ->
                Timber.e(exception, "Task failed: ${currentTask.content.url}")
                failedTasks.add(currentTask) // 将失败任务加入失败列表
                handleTaskCompletion() // 处理任务完成逻辑
            }
        )
    }

    /**
     * 处理任务完成逻辑
     */
    private fun handleTaskCompletion() {
        isRunning = false

        // 检查是否还有待处理任务
        if (taskQueue.isNotEmpty()) {
            processNextTask()
        } else if (failedTasks.isNotEmpty()) {
            // 如果没有待处理任务，但有失败任务，则重试失败任务
            retryFailedTasks()
        }
    }

    /**
     * 重新下载失败任务
     */
    private fun retryFailedTasks() {
        Timber.d("Retrying failed tasks: ${failedTasks.size}")
        taskQueue.addAll(failedTasks) // 将失败任务加入任务队列
        failedTasks.clear()
        processNextTask()
    }

    /**
     * 清空所有任务
     */
    fun clearAllTasks() {
        taskQueue.clear()
        failedTasks.clear()
        isRunning = false
    }

    /**
     * 获取当前任务队列状态
     */
    fun getTaskStatus(): String {
        return "Pending: ${taskQueue.size}, Failed: ${failedTasks.size}, Running: $isRunning"
    }
}
