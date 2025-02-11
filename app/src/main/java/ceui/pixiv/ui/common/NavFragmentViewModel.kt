package ceui.pixiv.ui.common

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.util.UUID

class NavFragmentViewModel(state: SavedStateHandle): ViewModel() {
    val fragmentUniqueId: String = state["fragmentUniqueId"]
        ?: UUID.randomUUID().toString().also {
            state["fragmentUniqueId"] = it
        }

    val createdTime: Long = state["createdTime"]
        ?: System.currentTimeMillis().also {
            state["createdTime"] = it
        }

    val viewCreatedTime = state.getLiveData<Long>("viewCreatedTime")

    private val doneOnceTasks: MutableSet<String> =
        state["doneOnceTasks"] ?: mutableSetOf<String>().also {
            state["doneOnceTasks"] = it
        }

    fun taskHasDone(taskId: String): Boolean {
        return doneOnceTasks.contains(taskId)
    }

    fun setTaskHasDone(taskId: String) {
        doneOnceTasks.add(taskId)
    }
}