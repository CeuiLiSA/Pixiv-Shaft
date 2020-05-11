package ceui.lisa.download

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.database.IllustTask
import ceui.lisa.utils.Channel
import ceui.lisa.utils.Common
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener1
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist.Listener1Model
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.greenrobot.eventbus.EventBus

class DListener : DownloadListener1() {
    private val _progresses = mutableMapOf<Int, DownloadHolder.Progress>()
    private val holders = mutableSetOf<DownloadHolder>()

    fun bind(taskId: Int, viewHolder: DownloadHolder) {
        _progresses[taskId] = _progresses[taskId] ?: DownloadHolder.Progress()
        holders.add(viewHolder)
    }

    override fun taskStart(task: DownloadTask, model: Listener1Model) {}

    override fun retry(task: DownloadTask, cause: ResumeFailedCause) {}

    override fun connected(task: DownloadTask, blockCount: Int, currentOffset: Long, totalLength: Long) {}

    override fun progress(task: DownloadTask, currentOffset: Long, totalLength: Long) {
        Common.showLog("progress " + task.filename + " " + currentOffset + "/" + totalLength)

        _progresses[task.id]?.apply {
            max = totalLength.toInt()
            progress = currentOffset.toInt()
            text = String.format("%s / %s",
                    FileSizeUtil.formatFileSize(currentOffset),
                    FileSizeUtil.formatFileSize(totalLength))
        }

        holders.forEach { view ->
            println(view.taskId)
            println(_progresses[view.taskId])
            _progresses[view.taskId]?.let { view.onUpdate(it) }
        }

    }

    override fun taskEnd(task: DownloadTask, cause: EndCause, realCause: Exception?, model: Listener1Model) {
        val illustTask = IllustTask()
        illustTask.downloadTask = task
        val endFile = task.file
        when (cause) {
            EndCause.COMPLETED -> {
                Common.showLog("taskEnd COMPLETED")
                try {
                    if (!TextUtils.isEmpty(task.filename) && task.filename!!.contains(".zip")) {
                        try {
                            val zipFile = ZipFile(task.file!!.path)
                            zipFile.extractAll(Shaft.sSettings.gifUnzipPath +
                                    task.filename!!.substring(0, task.filename!!.length - 4))
                            Common.showToast("图组ZIP解压完成")

                            //通知FragmentSingleIllust 开始播放gif
                            val channel: Channel<*> = Channel<Any?>()
                            channel.receiver = "FragmentSingleIllust"
                            EventBus.getDefault().post(channel)
                        } catch (e: ZipException) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                TaskQueue.get().removeTask(illustTask, true)
            }
            EndCause.ERROR -> {
                Common.showLog("taskEnd ERROR")
                Common.showToast("TASK ERROR " + endFile!!.name)
                realCause?.printStackTrace()
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
            EndCause.CANCELED -> {
                Common.showLog("taskEnd CANCELED")
                Common.showToast(endFile!!.name + "下载取消")
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
            EndCause.FILE_BUSY -> {
                Common.showLog("taskEnd FILE_BUSY")
                Common.showToast("TASK FILE_BUSY " + endFile!!.name)
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
            EndCause.SAME_TASK_BUSY -> {
                Common.showLog("taskEnd SAME_TASK_BUSY")
                Common.showToast("TASK SAME_TASK_BUSY " + endFile!!.name)
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
            EndCause.PRE_ALLOCATE_FAILED -> {
                Common.showLog("taskEnd PRE_ALLOCATE_FAILED")
                Common.showToast("TASK PRE_ALLOCATE_FAILED " + endFile!!.name)
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
            else -> {
                Common.showLog("taskEnd default")
                Common.showToast("TASK PRE_ALLOCATE_FAILED " + endFile!!.name)
                endFile.delete()
                TaskQueue.get().removeTask(illustTask, false)
            }
        }
    }
}