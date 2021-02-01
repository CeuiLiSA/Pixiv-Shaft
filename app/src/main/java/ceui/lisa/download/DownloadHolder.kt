package ceui.lisa.download

import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyDownloadTaskBinding

class DownloadHolder(binding: RecyDownloadTaskBinding) : ViewHolder<RecyDownloadTaskBinding>(binding) {
    var taskId = 0

    fun onUpdate(progress: Progress) {
        baseBind.progress.max = progress.max
        baseBind.progress.progress = progress.progress
        baseBind.currentSize.text = progress.text
    }

    class Progress {
        var progress: Int = 0
        var max: Int = 0
        var text: String = ""
    }
}
