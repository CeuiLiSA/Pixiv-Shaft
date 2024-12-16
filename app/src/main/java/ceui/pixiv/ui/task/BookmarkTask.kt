package ceui.pixiv.ui.task

import ceui.loxia.Client
import ceui.loxia.ObjectType

class BookmarkTask(
    private val objectId: Long,
    private val objectType: String
) : QueuedRunnable() {

    override suspend fun execute() {
        if (_status.value is TaskStatus.Executing || _status.value is TaskStatus.Finished) {
            return
        }
        try {
            _status.value = TaskStatus.Executing(0)
            if (objectType == ObjectType.NOVEL) {
//            Client.appApi.postBookmark()
            } else {
                Client.appApi.postBookmark(objectId)
            }
            _status.value = TaskStatus.Finished
        } catch (ex: Exception) {
            _status.value = TaskStatus.Error(ex)
            ex.printStackTrace()
        }
    }
}
