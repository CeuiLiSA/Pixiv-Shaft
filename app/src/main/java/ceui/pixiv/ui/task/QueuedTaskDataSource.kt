package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.loxia.requireTaskPool
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.works.buildPixivWorksFileName

class QueuedTaskDataSource(
    private val humanReadableTask: HumanReadableTask,
    private val activity: FragmentActivity
) : PagingAPIRepository<Illust>() {
    override suspend fun loadFirst(): KListShow<Illust> {
        return object : KListShow<Illust> {
            override val displayList: List<Illust>
                get() = loadIllustsFromCache(humanReadableTask.taskUUID) ?: listOf()
            override val nextPageUrl: String?
                get() = null
        }
    }

    override fun mapper(entity: Illust): List<ListItemHolder> {
        val items = mutableListOf<NamedUrl>()
        if (entity.page_count == 1) {
            entity.meta_single_page?.original_image_url?.let {
                items.add(NamedUrl(buildPixivWorksFileName(entity.id), it))
            }
        } else {
            entity.meta_pages?.forEachIndexed { index, page ->
                page.image_urls?.original?.let {
                    items.add(NamedUrl(buildPixivWorksFileName(entity.id, index), it))
                }
            }
        }
        return items.map { namedUrl ->
            val task =
                activity.requireTaskPool().getDownloadTask(namedUrl, activity.lifecycleScope).also {
                    TaskQueueManager.addTask(it)
                }
            QueuedTaskHolder(task, entity)
        }
    }
}