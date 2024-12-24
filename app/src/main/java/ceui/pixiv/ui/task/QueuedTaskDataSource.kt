package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.works.buildPixivWorksFileName

class QueuedTaskDataSource(
    private val taskUuid: String, private val activity: FragmentActivity
) : DataSource<Illust, KListShow<Illust>>(dataFetcher = {
    object : KListShow<Illust> {
        override val displayList: List<Illust>
            get() = loadIllustsFromCache(taskUuid) ?: listOf()
        override val nextPageUrl: String?
            get() = null
    }
}, responseStore = null, itemMapper = { illust ->
    val items = mutableListOf<NamedUrl>()
    if (illust.page_count == 1) {
        illust.meta_single_page?.original_image_url?.let {
            items.add(NamedUrl(buildPixivWorksFileName(illust.id), it))
        }
    } else {
        illust.meta_pages?.forEachIndexed { index, page ->
            page.image_urls?.original?.let {
                items.add(NamedUrl(buildPixivWorksFileName(illust.id, index), it))
            }
        }
    }
    items.map { namedUrl ->
        val task = TaskPool.getDownloadTask(namedUrl, activity).also {
            TaskQueueManager.addTask(it)
        }
        QueuedTaskHolder(task, illust)
    }
})