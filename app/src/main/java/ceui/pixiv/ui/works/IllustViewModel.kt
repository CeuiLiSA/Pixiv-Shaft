package ceui.pixiv.ui.works

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.MetaPage
import ceui.pixiv.ui.common.ImgDisplayViewModel
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskStatus
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import java.io.File

class IllustViewModel : ImgDisplayViewModel() {

    fun getGalleryHolders(illust: Illust, context: Context): List<GalleryHolder>? {
        return illust.meta_pages?.mapIndexedNotNull { index, metaPage ->
            if (metaPage.image_urls?.original?.isNotEmpty() == true) {
                val loadTask = taskFactory(
                    index,
                    NamedUrl(
                        buildPixivWorksFileName(illust.id, index),
                        metaPage.image_urls.original
                    ),
                    context
                )
                GalleryHolder(illust, index, loadTask) {
                    viewModelScope.launch {
                        loadTask.execute()
                    }
                }
            } else {
                null
            }
        }
    }
}