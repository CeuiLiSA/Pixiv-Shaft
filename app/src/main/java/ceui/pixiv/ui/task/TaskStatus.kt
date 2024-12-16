package ceui.pixiv.ui.task

sealed class TaskStatus {
    data object NotStart : TaskStatus()
    data class Executing(val percentage: Int) : TaskStatus()
    data object Finished : TaskStatus()
    data class Error(val exception: Exception) : TaskStatus()
}