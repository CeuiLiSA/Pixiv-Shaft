package ceui.pixiv.ui.task

import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTaskStatusBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class TaskStatusHolder(val downloadTask: DownloadTask) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return downloadTask.content.name == (other as? TaskStatusHolder)?.downloadTask?.content?.name
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return downloadTask.content == (other as? TaskStatusHolder)?.downloadTask?.content
    }
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

                binding.iconDone.isVisible = status is TaskStatus.Finished

                if (status is TaskStatus.Executing) {
                    binding.progress.isVisible = true
                    binding.progress.progress = status.percentage
                } else {
                    binding.progress.isVisible = false
                }

            }
        }
    }
}