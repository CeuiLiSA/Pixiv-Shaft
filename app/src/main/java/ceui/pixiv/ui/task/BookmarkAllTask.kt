package ceui.pixiv.ui.task

import ceui.loxia.Client
import ceui.loxia.ObjectType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

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
        } finally {
            optionalDelay()
        }
    }
}


class BookmarkAllTask(
    contentsProvider: () -> List<BookmarkTask>
) {
    private val pendingTasks = mutableListOf<BookmarkTask>()

    init {
        val contents = contentsProvider()
        pendingTasks.addAll(contents)
    }

    fun go() {
        MainScope().launch {
            pendingTasks.forEach {
                it.execute()
            }
        }
    }
}