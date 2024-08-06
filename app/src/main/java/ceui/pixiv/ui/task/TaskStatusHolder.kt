package ceui.pixiv.ui.task

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTaskStatusBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class TaskStatusHolder(val downloadTask: DownloadTask) : ListItemHolder() {
}

@ItemHolder(TaskStatusHolder::class)
class TaskStatusViewHolder(bd: CellTaskStatusBinding) : ListItemViewHolder<CellTaskStatusBinding, TaskStatusHolder>(bd) {

    override fun onBindViewHolder(holder: TaskStatusHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        binding.taskName.text = holder.downloadTask.content.name
        lifecycleOwner?.let {
            holder.downloadTask.status.observe(it) { status ->
                if (status is TaskStatus.NotStart) {
                    binding.taskStatus.text = "Not Start"
                } else if (status is TaskStatus.Executing) {
                    binding.taskStatus.text = "Executing"
                } else if (status is TaskStatus.Finished) {
                    binding.taskStatus.text = "Finished"
                } else if (status is TaskStatus.Error) {
                    binding.taskStatus.text = "Error"
                }
            }
        }
    }
}