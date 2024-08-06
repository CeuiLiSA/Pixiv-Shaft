package ceui.pixiv.task

import android.content.Context
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.ui.common.saveImageToGallery
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NamedUrl(
    val name: String,
    val url: String,
)

class DownloadAllTask(
    context: Context,
    contentsProvider: () -> List<NamedUrl>
) {

    init {
        MainScope().launch {
            withContext(Dispatchers.IO) {
                val contents = contentsProvider()
                val count = contents.size
                contents.forEachIndexed { index, content ->
                    try {
                        val file = Glide.with(context)
                            .asFile()
                            .load(GlideUrlChild(content.url))
                            .submit()
                            .get()
                        saveImageToGallery(context, file, content.name)
                        Common.showLog("DownloadAllTask finished ${content.name} ${index}/${count}")
                    } catch (ex: Exception) {
                        Common.showLog("DownloadAllTask error ${content.name} ${index}/${count}")
                        ex.printStackTrace()
                    } finally {
                        delay(5000)
                    }
                }
            }
        }
    }
}