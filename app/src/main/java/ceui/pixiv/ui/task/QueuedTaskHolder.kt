package ceui.pixiv.ui.task

import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.databinding.BindingAdapter
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellQueuedTaskBinding
import ceui.lisa.databinding.CellUsersYoruItemBinding
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.bottom.UsersYoriActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class QueuedTaskHolder(val downloadTask: DownloadTask, val illust: Illust) : ListItemHolder() {
    override fun getItemId(): Long {
        return downloadTask.taskId
    }
}

@ItemHolder(QueuedTaskHolder::class)
class QueuedTaskViewHolder(bd: CellQueuedTaskBinding) :
    ListItemViewHolder<CellQueuedTaskBinding, QueuedTaskHolder>(bd) {

    override fun onBindViewHolder(holder: QueuedTaskHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.task = holder.downloadTask
    }
}

@BindingAdapter("status_desc")
fun TextView.binding_setStatusDesc(taskStatus: TaskStatus?) {
    if (taskStatus != null) {
        if (taskStatus is TaskStatus.NotStart) {
            text = context.getString(R.string.task_status_not_started)
            setTextColor(Color.parseColor("#FFB332"))
        } else if (taskStatus is TaskStatus.Executing) {
            text = context.getString(R.string.task_status_downloading, taskStatus.percentage)
            setTextColor(Color.parseColor("#00FF94"))
        } else if (taskStatus is TaskStatus.Finished) {
            text = context.getString(R.string.task_status_done)
            setTextColor(Color.parseColor("#00FF94"))
        } else if (taskStatus is TaskStatus.Error) {
            text = context.getString(R.string.task_status_error)
            setTextColor(Color.parseColor("#FFB332"))
        } else {
            text = "taskStatus unknown"
        }
    } else {
        text = "taskStatus null"
    }
}

@BindingAdapter("status_percentage")
fun ProgressBar.binding_setStatusPercentage(taskStatus: TaskStatus?) {
    if (taskStatus != null) {
        if (taskStatus is TaskStatus.Executing) {
            progress = taskStatus.percentage
        }
    }
}