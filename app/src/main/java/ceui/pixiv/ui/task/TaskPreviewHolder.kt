package ceui.pixiv.ui.task

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTaskPreviewBinding
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class TaskPreviewHolder(val humanReadableTask: HumanReadableTask, val illusts: List<Illust>) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return humanReadableTask.taskUUID == (other as? TaskPreviewHolder)?.humanReadableTask?.taskUUID
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return humanReadableTask == (other as? TaskPreviewHolder)?.humanReadableTask
    }

    fun getIllustOrNull(index: Int): Illust? {
        return illusts.getOrNull(index)
    }
}

@ItemHolder(TaskPreviewHolder::class)
class TaskPreviewViewHolder(bd: CellTaskPreviewBinding) : ListItemViewHolder<CellTaskPreviewBinding, TaskPreviewHolder>(bd) {

    override fun onBindViewHolder(holder: TaskPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<TaskPreviewActionReceiver>()?.onClickTaskPreview(holder.humanReadableTask)
        }
        binding.taskSize.text = "共${holder.illusts.size}个作品"
    }
}

interface TaskPreviewActionReceiver {
    fun onClickTaskPreview(humanReadableTask: HumanReadableTask)
}
