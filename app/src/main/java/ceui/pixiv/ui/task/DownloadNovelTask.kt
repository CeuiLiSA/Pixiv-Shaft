package ceui.pixiv.ui.task

import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import ceui.pixiv.ui.works.buildPixivNovelFileName
import com.blankj.utilcode.util.PathUtils
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class DownloadNovelTask(
    private val coroutineScope: CoroutineScope,
    private val novel: Novel,
    private val webNovel: WebNovel? = null,
) : QueuedRunnable<Unit>() {

    override fun start(onNext: () -> Unit) {
        super.start(onNext)

        coroutineScope.launch {
            execute()
        }
    }

    override suspend fun execute() {
        if (_status.value is TaskStatus.Executing || _status.value is TaskStatus.Finished) {
            onIgnore()
            return
        }

        try {
            onStart()
            _status.value = TaskStatus.Executing(0)
            val stringBuffer = StringBuffer()

            val wNovel = if (webNovel != null) {
                delay(1500L)
                webNovel
            } else {
                val html = Client.appApi.getNovelText(novel.id).string()
                WebNovelParser.parsePixivObject(html)?.novel
            }

            if (wNovel == null) {
                throw RuntimeException("invalid web novel")
            }

            _status.value = TaskStatus.Executing(50)
            delay(1500L)

            // 构建内容，去除 HTML 标签
            stringBuffer.append("\n\n")
            stringBuffer.append("<===== Shaft Novel Start =====>")
            stringBuffer.append("\n\n")

            stringBuffer.append("标题：${novel.title}")
            stringBuffer.append("\n\n")

            stringBuffer.append("作者：${novel.user?.name}")
            stringBuffer.append("\n\n")

            stringBuffer.append("作者链接：https://www.pixiv.net/users/${novel.user?.id}")
            stringBuffer.append("\n\n")

            stringBuffer.append("小说链接：https://www.pixiv.net/novel/show.php?id=${novel.id}")
            stringBuffer.append("\n\n")

            stringBuffer.append("标签：${novel.tags?.map { it.name }?.joinToString(", ")}") // 去除 HTML 标签
            stringBuffer.append("\n\n")

            stringBuffer.append("简介：${replaceBrWithNewLine(novel.caption)}") // 去除 HTML 标签
            stringBuffer.append("\n\n")

            stringBuffer.append("正文：")
            stringBuffer.append("\n\n")
            stringBuffer.append(replaceBrWithNewLine(wNovel.text)) // 去除 HTML 标签
            stringBuffer.append("\n\n")
            stringBuffer.append("<===== Shaft Novel End =====>")
            stringBuffer.append("\n\n")

            val b = saveToDownloadsScopedStorage(context, buildPixivNovelFileName(novel), stringBuffer.toString())
            if (b) {
                ToastUtils.show(context.getString(R.string.string_181))
                _status.value = TaskStatus.Finished
                onEnd(Unit)
            } else {
                onError(RuntimeException("saveToDownloadsScopedStorage returned false"))
            }


        } catch (ex: Exception) {
            onError(ex)
        }
    }

    // 定义替换方法，将 <br> 替换为换行符
    companion object {
        fun replaceBrWithNewLine(input: String?): String {
            return input
                ?.replace(Regex("<br\\s*/?>"), "\n") // 替换 <br> 和 <br/> 为换行符
                ?.replace(Regex("<[^>]*>"), "") // 移除其他 HTML 标签
                ?: ""
        }
    }
}
